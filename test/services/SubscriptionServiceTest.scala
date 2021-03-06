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

package services

import java.util.UUID

import connectors.{EnrolmentStoreConnector, EtmpConnector, GovernmentGatewayAdminConnector}
import metrics.AwrsMetrics
import models._
import org.mockito.Matchers
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.OneServerPerSuite
import play.api.libs.json.Json
import play.api.test.Helpers._
import uk.gov.hmrc.http.logging.SessionId
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.test.UnitSpec
import utils.AwrsTestJson
import utils.AwrsTestJson._

import scala.concurrent.Future

class SubscriptionServiceTest extends UnitSpec with OneServerPerSuite with MockitoSugar with AwrsTestJson {
  val mockEtmpConnector = mock[EtmpConnector]
  val mockggAdminConnector = mock[GovernmentGatewayAdminConnector]
  val mockEnrolmentStoreConnector = mock[EnrolmentStoreConnector]

  val inputJson = api4EtmpLTDJson
  val safeId = "XA0001234567890"
  val successResponse = Json.parse(
    s"""{"processingDate":"2015-12-17T09:30:47Z","etmpFormBundleNumber":"123456789012345","awrsRegistrationNumber": "$testRefNo"}""")
  val ggEnrolResponse = Json.parse( """{}""")
  val failureResponse = Json.parse( """{"Reason": "Resource not found"}""")
  val address = BCAddressApi3(addressLine1 = "", addressLine2 = "")
  val updatedData = new UpdateRegistrationDetailsRequest(None, false, Some(Organisation("testName")), address, ContactDetails(), false, false)
  implicit val hc = new HeaderCarrier(sessionId = Some(SessionId(s"session-${UUID.randomUUID}")))


  object TestSubscriptionServiceGG extends SubscriptionService {
    override val ggAdminConnector = mockggAdminConnector
    override val enrolmentStoreConnector: EnrolmentStoreConnector = mockEnrolmentStoreConnector
    override val etmpConnector = mockEtmpConnector
    override val metrics = AwrsMetrics
    override val isEmacFeatureToggle = false
  }

  object TestSubscriptionServiceEMAC extends SubscriptionService {
    override val ggAdminConnector = mockggAdminConnector
    override val enrolmentStoreConnector: EnrolmentStoreConnector = mockEnrolmentStoreConnector
    override val etmpConnector = mockEtmpConnector
    override val metrics = AwrsMetrics
    override val isEmacFeatureToggle = true
  }

  "Subscription Service with EMAC switched off" should {
    behave like subscriptionServicesPart1(TestSubscriptionServiceGG)
    behave like subscriptionServicesPart2GG(TestSubscriptionServiceGG)
  }

  "Subscription Service with EMAC switched on" should {
    behave like subscriptionServicesPart1(TestSubscriptionServiceEMAC)
    behave like subscriptionServicesPart2EMAC(TestSubscriptionServiceEMAC)
  }

  def subscriptionServicesPart2EMAC(testSubscriptionService: => SubscriptionService): Unit = {
    "subscribe when we are passed valid json" in {
      when(mockEtmpConnector.subscribe(Matchers.any(),Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(OK, Some(successResponse))))
      when(mockEnrolmentStoreConnector.upsertEnrolment(Matchers.any(), Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(BAD_REQUEST, Some(failureResponse))))

      val result = testSubscriptionService.subscribe(inputJson,safeId, Some(testUtr), "SOP","postcode")
      val response = await(result)
      response.status shouldBe OK
      response.json shouldBe successResponse
    }

    "respond with Ok, when subscription works but enrolment store connector request fails with a Bad request but audit the Bad request" in {
      when(mockEtmpConnector.subscribe(Matchers.any(),Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(OK, Some(successResponse))))
      when(mockEnrolmentStoreConnector.upsertEnrolment(Matchers.any(), Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(BAD_REQUEST, Some(failureResponse))))
      val result = testSubscriptionService.subscribe(inputJson,safeId, Some(testUtr), "SOP","postcode")
      val response = await(result)
      response.status shouldBe OK
      response.json shouldBe successResponse
    }
  }

  def subscriptionServicesPart2GG(testSubscriptionService: => SubscriptionService): Unit = {
    "subscribe when we are passed valid json" in {
      when(mockEtmpConnector.subscribe(Matchers.any(),Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(OK, Some(successResponse))))
      when(mockggAdminConnector.addKnownFacts(Matchers.any(), Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(OK, Some(successResponse))))
      val result = testSubscriptionService.subscribe(inputJson,safeId, Some(testUtr), "SOP","postcode")
      val response = await(result)
      response.status shouldBe OK
      response.json shouldBe successResponse
    }

    "respond with Ok, when subscription works but gg admin request fails with a Bad request but audit the Bad request" in {
      when(mockEtmpConnector.subscribe(Matchers.any(),Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(OK, Some(successResponse))))
      when(mockggAdminConnector.addKnownFacts(Matchers.any(), Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(BAD_REQUEST, Some(failureResponse))))
      val result = testSubscriptionService.subscribe(inputJson,safeId, Some(testUtr), "SOP","postcode")
      val response = await(result)
      response.status shouldBe OK
      response.json shouldBe successResponse
    }
  }

  def subscriptionServicesPart1(testSubscriptionService: => SubscriptionService): Unit = {
    "use the correct Connectors" in {
      SubscriptionService.etmpConnector shouldBe EtmpConnector
      SubscriptionService.ggAdminConnector shouldBe GovernmentGatewayAdminConnector
    }

    "respond with BadRequest, when subscription request fails with a Bad request" in {
      when(mockEtmpConnector.subscribe(Matchers.any(),Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(BAD_REQUEST, Some(failureResponse))))
      val result = testSubscriptionService.subscribe(inputJson,safeId, Some(testUtr), "SOP","postcode")
      val response = await(result)
      response.status shouldBe BAD_REQUEST
      response.json shouldBe failureResponse
    }

    "respond with Ok, when a valid update subscription json is supplied" in {
      when(mockEtmpConnector.updateSubscription(Matchers.any(),Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(OK, Some(api6SuccessResponseJson))))
      val result = testSubscriptionService.updateSubcription(inputJson, testRefNo)
      val response = await(result)
      response.status shouldBe OK
    }

    "respond with BadRequest when update subscription json is invalid" in {
      when(mockEtmpConnector.updateSubscription(Matchers.any(),Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(BAD_REQUEST, Some(failureResponse))))
      val result = testSubscriptionService.updateSubcription(inputJson, testRefNo)
      val response = await(result)
      response.status shouldBe BAD_REQUEST
    }

    "respond with Ok, when a valid update Group Partner registration json is supplied" in {
      val updateSuccessResponse = Json.parse( """{"processingDate":"2015-12-17T09:30:47Z"}""")
      when(mockEtmpConnector.updateGrpRepRegistrationDetails(Matchers.any(),Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(OK, Some(updateSuccessResponse))))
      val result = testSubscriptionService.updateGrpRepRegistrationDetails(testRefNo,testSafeId,updatedData)
      val response = await(result)
      response.status shouldBe OK
    }

    "respond with BadRequest when update Group Partner registration json is invalid" in {
      when(mockEtmpConnector.updateGrpRepRegistrationDetails(Matchers.any(),Matchers.any())(Matchers.any()))
        .thenReturn(Future.successful(HttpResponse(BAD_REQUEST, Some(failureResponse))))
      val result = testSubscriptionService.updateGrpRepRegistrationDetails(testRefNo,testSafeId,updatedData)
      val response = await(result)
      response.status shouldBe BAD_REQUEST
    }
  }

}
