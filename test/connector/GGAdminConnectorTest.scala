/*
 * Copyright 2018 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package connector

import java.util.UUID
import java.util.concurrent.TimeUnit

import config.WSHttp
import connectors.GovernmentGatewayAdminConnector
import models.{KnownFact, KnownFactsForService}
import org.mockito.Matchers
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfter
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.OneServerPerSuite
import play.api.Play
import play.api.http.Status._
import play.api.libs.json.JsValue
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.config.{AppName, RunMode}
import uk.gov.hmrc.play.test.UnitSpec
import utils.AwrsTestJson
import utils.AwrsTestJson.testRefNo

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.logging.SessionId
import uk.gov.hmrc.play.microservice.config.LoadAuditingConfig

class GGAdminConnectorTest extends UnitSpec with OneServerPerSuite with MockitoSugar with BeforeAndAfter with AwrsTestJson {

  object TestAuditConnector extends AuditConnector {
    override lazy val auditingConfig = LoadAuditingConfig("auditing")
  }

  abstract class MockHttp extends WSHttp {
//    override val hooks = Seq(AuditingHook)

    override val auditConnector = TestAuditConnector

    override def appName = Play.configuration.getString("appName").getOrElse("awrs")
  }

  val mockWSHttp = mock[MockHttp]

  object TestGGAdminConnector extends GovernmentGatewayAdminConnector {
    override val http = mockWSHttp
    override val retryWait = 1 // override the retryWait as the wait time is irrelevant to the meaning of the test and reducing it speeds up the tests
  }

  // verification value that equals the amount of gg admin calls made, i.e. the first failed call plus 7 failed retries
  lazy val retries = TestGGAdminConnector.retryLimit + 1

  before {
    reset(mockWSHttp)
  }

  "GGAdminConnector" should {
    "for a successful submission, return OK response" in {

      val knownFact = KnownFactsForService(List(KnownFact("AWRS-REF-NO", testRefNo)))
      implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))
      when(mockWSHttp.POST[JsValue, HttpResponse](Matchers.any(), Matchers.any(), Matchers.any())(Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any())).thenReturn(Future.successful(HttpResponse(OK, responseJson = None)))
      val result = TestGGAdminConnector.addKnownFacts(knownFact, testRefNo)
      await(result).status shouldBe OK
    }

    "for a successful submission, if the first GG call fails, retry and if 2nd succeeds return OK response" in {

      val knownFact = KnownFactsForService(List(KnownFact("AWRS-REF-NO", testRefNo)))
      implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))
      when(mockWSHttp.POST[JsValue, HttpResponse](Matchers.any(), Matchers.any(), Matchers.any())(Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(INTERNAL_SERVER_ERROR, responseJson = None)))
        .thenReturn(Future.successful(HttpResponse(OK, responseJson = None)))
      val result = TestGGAdminConnector.addKnownFacts(knownFact, testRefNo)
      await(result).status shouldBe OK
      // verify that the call is made 2 times, i.e. the first failed call plus 1 successful retry
      verify(mockWSHttp, times(2)).POST[JsValue, HttpResponse](Matchers.any(), Matchers.any(), Matchers.any())(Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any())
    }

    "for a successful submission, if the first GG call fails, and all retries fail, return INTERNAL_SERVER_ERROR response" in {

      val knownFact = KnownFactsForService(List(KnownFact("AWRS-REF-NO", testRefNo)))
      implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))
      when(mockWSHttp.POST[JsValue, HttpResponse](Matchers.any(), Matchers.any(), Matchers.any())(Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(INTERNAL_SERVER_ERROR, responseJson = None)))
      val result = TestGGAdminConnector.addKnownFacts(knownFact, testRefNo)
      await(result)(FiniteDuration(10, TimeUnit.SECONDS)).status shouldBe INTERNAL_SERVER_ERROR
      // verify that the correct amount of retry calls are made, i.e. the first failed call plus the specified amount of failed retries
      verify(mockWSHttp, times(retries)).POST[JsValue, HttpResponse](Matchers.any(), Matchers.any(), Matchers.any())(Matchers.any(),Matchers.any(), Matchers.any(), Matchers.any())
    }

  }
}
