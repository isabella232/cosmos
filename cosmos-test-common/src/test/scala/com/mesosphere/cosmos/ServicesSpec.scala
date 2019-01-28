package com.mesosphere.cosmos

import com.mesosphere.cosmos.error.CosmosException
import com.mesosphere.cosmos.error.ServiceUnavailable
import io.lemonlabs.uri.dsl._
import com.twitter.conversions.storage._
import com.twitter.finagle.http.RequestBuilder
import com.twitter.util.Await
import com.twitter.util.Return
import io.netty.handler.codec.http.HttpResponseStatus
import org.scalatest.FreeSpec

final class ServicesSpec extends FreeSpec {

  "Services" - {

    "adminRouterClient should" - {
      "be able to be created with a well formed URI with a domain that doesn't resolve" in {
        val url = "http://some.domain.that-im-pretty-sure-doesnt-exist-anywhere"
        val Return(client) = Services.adminRouterClient(url, 5.megabytes)

        try {
          val request = RequestBuilder().url(url).buildGet()
          Await.result(client(request))
        } catch {
          case e: CosmosException if e.error.isInstanceOf[ServiceUnavailable] =>
            assertResult(HttpResponseStatus.SERVICE_UNAVAILABLE)(e.error.status)
        } finally {
          val _ = Await.ready(client.close())
        }
      }
    }

  }
}
