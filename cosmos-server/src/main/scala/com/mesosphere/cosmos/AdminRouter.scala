package com.mesosphere.cosmos

import _root_.io.circe.JsonObject
import com.mesosphere.cosmos.http.RequestSession
import com.mesosphere.cosmos.thirdparty.adminrouter.model.DcosVersion
import com.mesosphere.cosmos.thirdparty.marathon.model.AppId
import com.mesosphere.cosmos.thirdparty.marathon.model.Deployment
import com.mesosphere.cosmos.thirdparty.marathon.model.MarathonAppResponse
import com.mesosphere.cosmos.thirdparty.marathon.model.MarathonAppsResponse
import com.mesosphere.cosmos.thirdparty.mesos.master.model._
import com.twitter.finagle.http._
import com.twitter.util.Future

class AdminRouter(
  adminRouterClient: AdminRouterClient,
  marathon: MarathonClient,
  mesos: MesosMasterClient
) {

  def createApp(appJson: JsonObject)(implicit session: RequestSession): Future[Response] = {
    marathon.createApp(appJson)
  }

  def getAppOption(
    appId: AppId
  )(
    implicit session: RequestSession
  ): Future[Option[MarathonAppResponse]] = {
    marathon.getAppOption(appId)
  }

  def getApp(appId: AppId)(implicit session: RequestSession): Future[MarathonAppResponse] = {
    marathon.getApp(appId)
  }

  def modifyApp(
    appId: AppId,
    force: Boolean
  )(
    f: JsonObject => JsonObject
  )(
    implicit session: RequestSession
  ): Future[Response] = {
    marathon.modifyApp(appId, force)(f)
  }

  def update(
    appId: AppId,
    appJson: JsonObject
  )(
    implicit session: RequestSession
  ): Future[Response] = {
    marathon.update(appId, appJson)
  }

  def listApps()(implicit session: RequestSession): Future[MarathonAppsResponse] = {
    marathon.listApps()
  }

  def deleteApp(
    appId: AppId,
    force: Boolean = false
  )(
    implicit session: RequestSession
  ): Future[Response] = {
    marathon.deleteApp(appId, force)
  }

  def tearDownFramework(
    frameworkId: String
  )(
    implicit session: RequestSession
  ): Future[MesosFrameworkTearDownResponse] = {
    mesos.tearDownFramework(frameworkId)
  }

  def getFrameworks(
    frameworkName: String
  )(
    implicit session: RequestSession
  ): Future[List[Framework]] = {
    mesos.getFrameworks(frameworkName)
  }

  def getDcosVersion()(implicit session: RequestSession): Future[DcosVersion] = {
    adminRouterClient.getDcosVersion()
  }

  def getSdkServiceFrameworkIds(service: AppId, apiVersion: String)(implicit
    session: RequestSession
  ): Future[List[String]] = {
    adminRouterClient.getSdkServiceFrameworkIds(service, apiVersion)
  }

  def getSdkServicePlanStatus(
    service: AppId,
    apiVersion: String,
    plan: String
  )(
    implicit session: RequestSession
  ): Future[Response] = {
    adminRouterClient.getSdkServicePlanStatus(service, apiVersion, plan)
  }

  def postCustomPackageInstall(
    managerId: AppId,
    body: rpc.v1.model.InstallRequest
  )(
    implicit session: RequestSession
  ): Future[Response] = {
    adminRouterClient.postCustomPackageInstall(managerId, body)
  }

  def postCustomPackageUninstall(
    managerId: AppId,
    body: rpc.v1.model.UninstallRequest
  )(
   implicit session: RequestSession
  ): Future[Response] = {
    adminRouterClient.postCustomPackageUninstall(managerId, body)
  }

  def postCustomServiceDescribe(
    managerId: AppId,
    body: rpc.v1.model.ServiceDescribeRequest
  )(
    implicit session: RequestSession
  ): Future[Response] = {
    adminRouterClient.postCustomServiceDescribe(managerId, body)
  }

  def postCustomServiceUpdate(
    managerId: AppId,
    body: rpc.v1.model.ServiceUpdateRequest
   )(
     implicit session: RequestSession
   ): Future[Response] = {
    adminRouterClient.postCustomServiceUpdate(managerId, body)
  }

  def listDeployments()(implicit session: RequestSession): Future[List[Deployment]] = {
    marathon.listDeployments()
  }
}
