package controllers.api

import auth.WithDomain
import domain._
import engine.InvoiceEngine
import org.bouncycastle.util.encoders.Base64
import play.Logger
import play.api.libs.json._
import play.api.mvc.Controller
import play.modules.reactivemongo.MongoController
import play.modules.reactivemongo.json.collection.JSONCollection
import repository.Repositories
import securesocial.core.{BasicProfile, RuntimeEnvironment}

import scala.concurrent.Future

class InvoiceApiController(override implicit val env: RuntimeEnvironment[BasicProfile])
  extends Controller
  with Repositories
  with MongoController
  with InvoiceSerializer
  with AccountSerializer
  with AffectationSerializer
  with AffectationReqSerializer
  with InvoiceEngine {


  def createAndPushInvoice = SecuredAction(WithDomain()) { implicit request =>
    request.body.asJson match {
      case Some(json) => json.validate(invoiceReqFormat) match {

        case errors: JsError =>
          BadRequest(errors.toString).as("application/json")

        case result: JsResult[InvoiceRequest] =>
          val generatedPdfDocument = invoiceRequestToPdfBytes(result.get)

          val invoiceId = insertInvoice(request, result.get, generatedPdfDocument)

          Ok(routes.InvoiceApiController.getPdfByInvoice(invoiceId.stringify).absoluteURL())
      }
      case None => request.body.asFormUrlEncoded match {
        case Some(body) =>
          val invoiceRequest = invoiceFromForm(body)

          val shouldUpload = body.get("shouldUpload").map(_.head).exists(_.equalsIgnoreCase("on"))

          val generatedPdfDocument = invoiceRequestToPdfBytes(invoiceRequest)

          if (shouldUpload) {
            val invoiceId = insertInvoice(request, invoiceRequest, generatedPdfDocument)
          }
          Ok(generatedPdfDocument).as("application/pdf")

        case None => Ok("no go")
      }
    }
  }

  // TODO put body in Invoice Repository
  def getLastInvoiceNumber = SecuredAction(WithDomain()).async {
    db.collection[JSONCollection]("invoiceNumber")
      .find(Json.obj())
      .one[InvoiceNumber]
      .map(mayBeObj => Ok(Json.toJson(mayBeObj.get)))
  }

  // TODO put body in Invoice Repository
  def reset(value: Int) = SecuredAction(WithDomain()) {
    Logger.info(s"reset value of invoiceNumber to $value")
    db.collection[JSONCollection]("invoiceNumber")
      .update(Json.obj(), Json.toJson(InvoiceNumber(value)))
    Ok
  }

  def find = SecuredAction(WithDomain()).async { implicit request =>
    invoiceRepository
      .find
      .map(invoices => Ok(Json.toJson(invoices)))
  }

  def findDelayedInvoices = SecuredAction(WithDomain()).async { implicit request =>
    invoiceRepository
      .findInProgress
      .map(invoices => Ok(Json.toJson(invoices.filter(invoice => invoice.isDelayed))))
  }

  def addStatusToInvoice(oid: String, status: String) = SecuredAction(WithDomain()).async { implicit request =>
    invoiceRepository.updateInvoiceStatus(oid, status, request.user.email.get).map {
      case false => Ok
      case true => InternalServerError
    }
  }

  def cancelInvoice(invoiceId: String) = SecuredAction(WithDomain()).async(parse.json) { implicit request =>
    invoiceRepository
      .find(invoiceId)
      .flatMap {
      case (mayBeInvoice: Option[Invoice]) => mayBeInvoice match {
        case Some(invoice) => {
          Logger.info("Loaded invoice, canceling...")
          val generatedPdfDocument = addCanceledWatermark(invoice.pdfDocument.data)

          invoiceRepository.cancelInvoice(invoiceId, generatedPdfDocument, request.user.email.get).map( hasErrors =>
            if (hasErrors) Logger.error(s"unable to cancel invoice $invoiceId")
          )

          // delete affectations from this invoice, see issue #36
          Logger.info(s"Remove allocations associated to invoice $invoiceId")
          allocationRepository.removeByInvoice(invoiceId).map( hasErrors =>
            if (hasErrors) Logger.error(s"unable to delete allocations of invoice $invoiceId")
          )

          // remove invoice id from activity if needed
          Logger.info(s"Unset invoice $invoiceId from associated activity if needed")
          activityRepository.unsetInvoiceFromActivity(invoiceId)
          .map {
            case true =>
              Logger.error(s"unable to unset invoice $invoiceId from associated activity")
              InternalServerError
            case false => Ok
          }
        }
        case None => Future(InternalServerError)
      }
      case _ => Future(BadRequest)
    }
  }


  def affectToAccount(oid: String) = SecuredAction(WithDomain()).async(parse.json) { implicit request =>
    invoiceRepository
      .find(oid)
      .flatMap {
      case (mayBeInvoice: Option[Invoice]) =>
        (for (invoice <- mayBeInvoice) yield {
          Logger.info("Loaded invoice, creating allocations...")

          allocationRepository.removeByInvoice(invoice._id.stringify) // TODO remove allocations after error checking

          val futures = request.body.as[JsArray].value.map { affectationRequest =>

            affectationRequest.validate(affectationReqFormatter) match {
              case errors: JsError => Future(true)
              case result: JsResult[AffectationRequest] => {
                val allocation = IncomeAffectation(result.get.account, result.get.value, invoice._id)

                Logger.info(affectationRequest.toString())

                allocationRepository.save(allocation)
              }
            }
          }

          checkFailures(futures).map {
            case true => InternalServerError
            case false =>
              val status = if (invoice.isAllocated) "reallocated" else "allocated"
              Logger.info(s"Add status $status to invoice $oid")
              invoiceRepository.updateInvoiceStatus(oid, status, request.user.email.get).map(hasErrors =>
                if (hasErrors) Logger.error(s"unable to add status $status to invoice $oid")
              )
              Ok
          }
        }).getOrElse(Future(InternalServerError))
      case _ => Future(BadRequest)
    }
  }

  private def checkFailures(futures: Seq[Future[Boolean]]): Future[Boolean] = {
    Future.sequence(futures)
      .map(_.foldLeft(false)((acc, current) => acc || current))
  }

  def getPdfByInvoice(oid: String) = SecuredAction(WithDomain()).async {
    invoiceRepository.retrievePDF(oid)
      .map {
      case None => BadRequest
      case Some(doc) => Ok(Base64.decode(doc)).as("application/pdf")
    }
  }

}
