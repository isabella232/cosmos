package com.mesosphere.cosmos

import com.mesosphere.cosmos.Uris._
import com.mesosphere.cosmos.error.CosmosException
import com.mesosphere.cosmos.error.Forbidden
import com.mesosphere.cosmos.error.ServiceUnavailable
import com.mesosphere.cosmos.error.Unauthorized
import io.lemonlabs.uri.Uri
import com.twitter.finagle.ChannelException
import com.twitter.finagle.Filter
import com.twitter.finagle.Http
import com.twitter.finagle.NoBrokersAvailableException
import com.twitter.finagle.RequestException
import com.twitter.finagle.Service
import com.twitter.finagle.SimpleFilter
import com.twitter.finagle.Stack
import com.twitter.finagle.http.Request
import com.twitter.finagle.http.Response
import com.twitter.finagle.http.Status
import com.twitter.finagle.http.param.MaxResponseSize
import com.twitter.util.Future
import com.twitter.util.StorageUnit
import com.twitter.util.Try

object Services {
  def adminRouterClient(
    uri: Uri,
    maxResponseSize: StorageUnit
  ): Try[Service[Request, Response]] = {
    httpClient("adminRouter", uri, maxResponseSize)
  }

  def marathonClient(
    uri: Uri,
    maxResponseSize: StorageUnit
  ): Try[Service[Request, Response]] = {
    httpClient("marathon", uri, maxResponseSize)
  }

  def mesosClient(
    uri: Uri,
    maxResponseSize: StorageUnit
  ): Try[Service[Request, Response]] = {
    httpClient("mesos", uri, maxResponseSize)
  }

  def httpClient(
    serviceName: String,
    uri: Uri,
    maxResponseSize: StorageUnit,
    postAuthFilter: SimpleFilter[Request, Response] = Filter.identity
  ): Try[Service[Request, Response]] = {
    extractHostAndPort(uri) map { case ConnectionDetails(hostname, port, tls) =>
      val cBuilder = if (tls) {
        Http.client.withTls(hostname).withStack(Http.client.stack.remove(Stack.Role("Retries")))
      } else {
        Http.client.withStack(Http.client.stack.remove(Stack.Role("Retries")))
      }

      CustomLoggingFilter.andThen(
        new ConnectionExceptionHandler(serviceName).andThen(
          new AuthFilter(serviceName).andThen(
            postAuthFilter.andThen(
              cBuilder
                .configured(MaxResponseSize(maxResponseSize))
                .newService(s"$hostname:$port", serviceName)
            )
          )
        )
      )
    }
  }

  private[this] class ConnectionExceptionHandler(serviceName: String) extends SimpleFilter[Request, Response] {
    override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
      service(request)
        .rescue {
          case ce: ChannelException =>
            Future.exception(
              CosmosException(ServiceUnavailable(serviceName), ce)
            )
          case e: NoBrokersAvailableException =>
            Future.exception(
              CosmosException(ServiceUnavailable(serviceName), e)
            )
          case e: RequestException =>
            Future.exception(
              CosmosException(ServiceUnavailable(serviceName), e)
            )
        }
    }
  }

  private[this] class AuthFilter(serviceName: String) extends SimpleFilter[Request, Response] {
    override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
      service(request)
        .map { response =>
          response.status match {
            case Status.Unauthorized =>
              throw Unauthorized(serviceName, response.headerMap.get("WWW-Authenticate")).exception
            case Status.Forbidden =>
              throw Forbidden(serviceName).exception
            case _ =>
              response
          }
        }
    }
  }

}
