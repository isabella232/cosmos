package com.mesosphere.cosmos.handler

import cats.data.Xor
import com.mesosphere.cosmos.AdminRouter
import com.mesosphere.cosmos.CirceError
import com.mesosphere.cosmos.PackageNotFound
import com.mesosphere.cosmos.converter.Label._
import com.mesosphere.cosmos.converter.Universe.internalPackageDefinitionToInstalledPackageInformation
import com.mesosphere.cosmos.http.RequestSession
import com.mesosphere.cosmos.label
import com.mesosphere.cosmos.label.v1.circe.Decoders._
import com.mesosphere.cosmos.repository.CosmosRepository
import com.mesosphere.cosmos.rpc
import com.mesosphere.cosmos.thirdparty
import com.mesosphere.universe
import com.netaporter.uri.Uri
import com.netaporter.uri.dsl.stringToUri
import com.twitter.bijection.Conversion.asMethod
import com.twitter.util.Future
import io.circe.parse._
import java.nio.charset.StandardCharsets
import java.util.Base64

private[cosmos] final class ListHandler(
  adminRouter: AdminRouter,
  repositories: (Uri) => Future[Option[CosmosRepository]]
) extends EndpointHandler[rpc.v1.model.ListRequest, rpc.v1.model.ListResponse] {

  private case class App(
    id: thirdparty.marathon.model.AppId,
    pkgName: String,
    pkgReleaseVersion: universe.v3.model.PackageDefinition.ReleaseVersion,
    repoUri: String,
    pkgMetadata: Option[String]
  )

  override def apply(
    request: rpc.v1.model.ListRequest
  )(
    implicit session: RequestSession
  ): Future[rpc.v1.model.ListResponse] = {
    for {
      apps <- getApplications(adminRouter, request)
      repoAssocs <- getRepositoryAssociations(repositories, apps)
      installs <- getInstallations(repoAssocs)
    } yield {
      rpc.v1.model.ListResponse(installs.sortBy(install =>
        (install.packageInformation.packageDefinition.name, install.appId)))
    }
  }

  private[this] def getApplications(
    adminRouter: AdminRouter,
    request: rpc.v1.model.ListRequest
  )(
    implicit session: RequestSession
  ): Future[Seq[App]] = {

    def satisfiesRequest(app: thirdparty.marathon.model.MarathonApp): Boolean = {
      // corner case: packageReleaseVersion will be None if parsing the label fails
      (app.packageReleaseVersion, app.packageName, app.packageRepository) match {
        case (Some(_), Some(pkgName), Some(_)) =>
          request.packageName.forall(_ == pkgName) && request.appId.forall(_ == app.id)
        case _ => false
      }
    }

    adminRouter.listApps().map { response =>
      response.apps.collect{
        case app if satisfiesRequest(app) =>
          App(
            app.id,
            app.packageName.get,
            app.packageReleaseVersion.get,
            app.packageRepository.get,
            app.packageMetadata
          )
      }
    }
  }

  private[this] def getRepositoryAssociations(
    repositories: (Uri) => Future[Option[CosmosRepository]],
    apps: Seq[App]
  ): Future[Seq[(App, Option[CosmosRepository])]] = {
    Future.collect {
      apps.map { app =>
        repositories(app.repoUri).map(repo => (app, repo))
      }
    }
  }

  private[this] def getInstallations(
    assocs: Seq[(App, Option[CosmosRepository])]
  )(
    implicit session: RequestSession
  ): Future[Seq[rpc.v1.model.Installation]] = {
    Future.collect {
      assocs.map {
        case (app, Some(repo)) =>
          repo
            .getPackageByReleaseVersion(app.pkgName, app.pkgReleaseVersion)
            .map(pkg => pkg.as[rpc.v1.model.InstalledPackageInformation])
            .handle {
              case PackageNotFound(_) =>
                /* If for some reason the package is not part of the repo anymore we should use
                 * the information stored with the application
                 */
                decodeInstalledPackageInformation(app)
            }
            .map { pkgInfo =>
              rpc.v1.model.Installation(app.id, pkgInfo)
            }
        case (app, None) =>
          Future.value(rpc.v1.model.Installation(app.id, decodeInstalledPackageInformation(app)))
      }
    }
  }

  private[this] def decodeInstalledPackageInformation(
    app: App
  ): rpc.v1.model.InstalledPackageInformation = {
    val pkgMetadata = app.pkgMetadata.getOrElse("")
    val pkgInfo = new String(
      Base64.getDecoder.decode(pkgMetadata),
      StandardCharsets.UTF_8
    )
    decode[label.v1.model.PackageMetadata](pkgInfo) match {
      case Xor.Left(err) => throw CirceError(err)
      case Xor.Right(pkg) => pkg.as[rpc.v1.model.InstalledPackageInformation]
    }
  }

}
