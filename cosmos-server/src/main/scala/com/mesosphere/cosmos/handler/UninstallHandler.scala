package com.mesosphere.cosmos.handler

import com.mesosphere.cosmos.AdminRouter
import com.mesosphere.cosmos.circe.Decoders
import com.mesosphere.cosmos.error.AmbiguousAppId
import com.mesosphere.cosmos.error.CosmosException
import com.mesosphere.cosmos.error.FailedToStartUninstall
import com.mesosphere.cosmos.error.IncompleteUninstall
import com.mesosphere.cosmos.error.JsonParsingError
import com.mesosphere.cosmos.error.MarathonAppDeleteError
import com.mesosphere.cosmos.error.MultipleFrameworkIds
import com.mesosphere.cosmos.error.PackageNotInstalled
import com.mesosphere.cosmos.error.ServiceUnavailable
import com.mesosphere.cosmos.error.UninstallNonExistentAppForPackage
import com.mesosphere.cosmos.handler.UninstallHandler._
import com.mesosphere.cosmos.http.RequestSession
import com.mesosphere.cosmos.repository.PackageCollection
import com.mesosphere.cosmos.rpc
import com.mesosphere.cosmos.service.CustomPackageManagerRouter
import com.mesosphere.cosmos.service.ServiceUninstaller
import com.mesosphere.cosmos.thirdparty.marathon.model.AppId
import com.mesosphere.cosmos.thirdparty.marathon.model.MarathonApp
import com.mesosphere.error.ResultOps
import com.mesosphere.universe
import com.mesosphere.universe.bijection.UniverseConversions._
import com.twitter.bijection.Conversion.asMethod
import com.twitter.finagle.http.Status
import com.twitter.util.Future
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax._

private[cosmos] final class UninstallHandler(
  adminRouter: AdminRouter,
  packageCollection: PackageCollection,
  uninstaller: ServiceUninstaller,
  customPackageManagerRouter: CustomPackageManagerRouter
) extends CustomEndpointHandler[rpc.v1.model.UninstallRequest, rpc.v1.model.UninstallResponse] {

  private[this] lazy val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  private type FwIds = List[String]

  override def apply(
    req: rpc.v1.model.UninstallRequest
  )(
    implicit session: RequestSession
  ): Future[rpc.v1.model.UninstallResponse] = {
    getMarathonApps(req.packageName, req.appId)
      .map(apps => createUninstallOperations(req.packageName, apps))
      .map { uninstallOps =>
        val all = req.all.contains(true)
        if (all || uninstallOps.size <= 1) {
          uninstallOps
        } else {
          throw AmbiguousAppId(req.packageName, uninstallOps.map(_._2.appId)).exception
        }
      }
      .flatMap { apps =>
        Future.collect {
          apps.map { case (app, op) =>
            customPackageManagerRouter
              .getCustomPackageManagerId(
                req.managerId,
                Some(req.packageName),
                app.packageVersion,
                Some(req.appId.getOrElse(app.id))
              )
              .flatMap {
                case Some((Some(managerId), Some(pkgName), Some(pkgVersion))) if !managerId.isEmpty =>
                  logger.debug(s"Request [$req] requires a custom manager: [$managerId]")
                  customPackageManagerRouter.callCustomPackageUninstall(
                    req,
                    managerId,
                    pkgName,
                    pkgVersion,
                    req.appId.getOrElse(app.id)
                  )
                case _ => runUninstall(op, app)
              }
          }
        }.map(_.map(_.results).toList.flatten).map(rpc.v1.model.UninstallResponse(_))
      }
  }

  private def runUninstall(
    uninstallOp: UninstallOperation,
    app: MarathonApp
  )(
    implicit session: RequestSession
  ): Future[rpc.v1.model.UninstallResponse] = {
    Future
      .join(
        uninstallOp.uninstallType match {
          case MarathonUninstall => runMarathonUninstall(uninstallOp)
          case SdkUninstall => runSdkUninstall(uninstallOp)
        },
        getPackageWithSource(packageCollection, app).map(_.flatMap(_._1.postUninstallNotes))
      )
      .map { case (details, postUninstallNotes) =>
        rpc.v1.model.UninstallResponse(
          List(
            rpc.v1.model.UninstallResult(
              details.packageName,
              details.appId,
              details.packageVersion,
              postUninstallNotes
            )
          )
        )
      }
  }

  private[this] def runMarathonUninstall(
    op: UninstallOperation
  )(
    implicit session: RequestSession
  ): Future[UninstallDetails] = {
    destroyMarathonApp(op.appId) flatMap { _ =>
      op.frameworkName match {
        case Some(fwName) =>
          lookupFrameworkIds(fwName).flatMap {
            case Nil =>
              Future.value(UninstallDetails.from(op))
            case fwId :: Nil =>
              adminRouter.tearDownFramework(fwId)
                .map { _ =>
                  UninstallDetails.from(op).copy(frameworkId = Some(fwId))
                }
            case all =>
              throw MultipleFrameworkIds(op.packageName, op.packageVersion, fwName, all).exception
          } handle {
            case su@CosmosException(ServiceUnavailable(_, _), _, _) =>
              throw CosmosException(IncompleteUninstall(op.packageName), su)
          }
        case None =>
          Future.value(UninstallDetails.from(op))
      }
    }
  }

  private[this] def runSdkUninstall(
    op: UninstallOperation
  )(
    implicit session: RequestSession
  ): Future[UninstallDetails] = {
    adminRouter.getApp(op.appId)
      .flatMap { response =>
        val sdkApiVersion = response.app.getLabel(SdkVersionLabel).getOrElse("v1")
        adminRouter.getSdkServiceFrameworkIds(op.appId, sdkApiVersion)
      }
      .handle { case _ => Nil }
      .flatMap { frameworkIds =>
        adminRouter.modifyApp(op.appId, force = true)(setMarathonUninstall).map { response =>
          response.status match {
            case Status.Ok =>
              val deploymentId = parseDeploymentId(response.contentString, op)
              val _ = uninstaller.uninstall(op.appId, frameworkIds.toSet, deploymentId)
                .onFailure { exception =>
                  logger.error(s"Background uninstall for ${op.appId} failed", exception)
                }

              UninstallDetails.from(op)
            case _ =>
              logger.error("Encountered error in marathon request {}", response.contentString)
              throw FailedToStartUninstall(
                op.appId,
                s"Encountered error in marathon request ${response.contentString}"
              ).exception
          }
        }
      }
  }

  private[this] def parseDeploymentId(content: String, op: UninstallOperation): String = {
    try {
      Decoders.parse(content).getOrThrow.hcursor.get[String]("deploymentId") match {
        case Right(deploymentId) => deploymentId
        case Left(_) =>
          throw FailedToStartUninstall(
            op.appId,
            DeploymentIdErrorMessage.format(content)
          ).exception
      }
    } catch {
      case ex: CosmosException if ex.error.isInstanceOf[JsonParsingError] =>
        throw FailedToStartUninstall(op.appId, DeploymentIdErrorMessage.format(content)).exception
    }
  }

  private[this] def setMarathonUninstall(appJson: JsonObject): JsonObject = {
    appJson.add(
      envJsonField,
      Json.fromJsonObject(
        appJson(envJsonField)
          .flatMap(_.asObject)
          .getOrElse(JsonObject())
          .add(SdkUninstallEnvvar, "true".asJson)
      )
    )
  }

  private def lookupFrameworkIds(
    fwName: String
  )(
    implicit session: RequestSession
  ): Future[FwIds] = {
    adminRouter.getFrameworks(fwName).map(_.map(_.id))
  }

  private def destroyMarathonApp(
    appId: AppId
  )(
    implicit session: RequestSession
  ): Future[MarathonAppDeleteSuccess] = {
    adminRouter.deleteApp(appId, force = true).map { resp =>
      resp.status match {
        case Status.Ok => MarathonAppDeleteSuccess()
        case _ => {
          logger.info(s"Marathon responded with error : [${resp.status}], ${resp.contentString}")
          throw MarathonAppDeleteError(appId).exception
        }
      }
    }
  }

  private[this] def getMarathonApps(
    packageName: String,
    appId: Option[AppId]
  )(
    implicit session: RequestSession
  ): Future[List[MarathonApp]] = {
    appId match {
      case Some(id) =>
        adminRouter.getAppOption(id).map {
          case Some(appResponse) => List(appResponse.app)
          case _ => throw UninstallNonExistentAppForPackage(packageName, id).exception
        }
      case None =>
        adminRouter.listApps().map(_.apps)
    }
  }

  private[this] def createUninstallOperations(
    requestedPackageName: String,
    apps: List[MarathonApp]
  ): List[(MarathonApp, UninstallOperation)] = {
    val uninstallOperations = for {
      app <- apps
      packageName <- app.getLabel(MarathonApp.nameLabel)
      if packageName == requestedPackageName
    } yield {
      (
        app,
        UninstallOperation(
          appId = app.id,
          packageName = packageName,
          frameworkName = app.getLabel(MarathonApp.frameworkNameLabel),
          packageVersion = app.packageVersion.as[Option[universe.v2.model.PackageDetailsVersion]],
          uninstallType = if (app.getLabel(SdkServiceLabel).isDefined) SdkUninstall else MarathonUninstall
        )
      )
    }

    if (uninstallOperations.isEmpty) {
      throw PackageNotInstalled(requestedPackageName).exception
    }
    uninstallOperations
  }

  override def tryCustomPackageManager(
    req: rpc.v1.model.UninstallRequest
  )(
    implicit session: RequestSession
  ): Future[Option[rpc.v1.model.UninstallResponse]] = throw new NotImplementedError()
}

object UninstallHandler {
  val SdkServiceLabel: String = "DCOS_COMMONS_UNINSTALL"
  val SdkVersionLabel: String = "DCOS_COMMONS_API_VERSION"
  val SdkUninstallEnvvar: String = "SDK_UNINSTALL"
  val envJsonField: String = "env"
  val DeploymentIdErrorMessage: String = "Marathon update response is not a JSON Object: %s"

  private case class MarathonAppDeleteSuccess()

  private[handler] case class UninstallOperation(
    appId: AppId,
    packageName: String,
    packageVersion: Option[universe.v2.model.PackageDetailsVersion],
    frameworkName: Option[String],
    uninstallType: UninstallType
  )

  private[handler] case class UninstallDetails(
    appId: AppId,
    packageName: String,
    packageVersion: Option[universe.v2.model.PackageDetailsVersion],
    frameworkName: Option[String] = None,
    frameworkId: Option[String] = None
  )

  private case object UninstallDetails {
    def from(uninstallOperation: UninstallOperation): UninstallDetails = {
      UninstallDetails(
        appId = uninstallOperation.appId,
        packageName = uninstallOperation.packageName,
        packageVersion = uninstallOperation.packageVersion,
        frameworkName = uninstallOperation.frameworkName
      )
    }
  }

  private[handler] sealed trait UninstallType

  private[handler] case object MarathonUninstall extends UninstallType

  private[handler] case object SdkUninstall extends UninstallType

}
