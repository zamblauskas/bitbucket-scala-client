package com.codacy.client.bitbucket.client

import com.codacy.client.bitbucket.util.HTTPStatusCodes
import com.ning.http.client.AsyncHttpClient
import play.api.libs.json.{JsValue, Json, Reads}
import play.api.libs.oauth._
import play.api.libs.ws.ning.NingWSClient

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, SECONDS}

class BitbucketClient(key: String, secretKey: String, token: String, secretToken: String) {

  private lazy val KEY = ConsumerKey(key, secretKey)
  private lazy val TOKEN = RequestToken(token, secretToken)

  private lazy val requestTimeout = Duration(10, SECONDS)
  private lazy val requestSigner = OAuthCalculator(KEY, TOKEN)

  /*
   * Does an API request and parses the json output into a class
   */
  def execute[T](request: Request[T])(implicit reader: Reads[T]): RequestResponse[T] = {
    get(request.url) match {
      case Right(json) => RequestResponse(json.asOpt[T])
      case Left(error) => RequestResponse(None, error.detail, hasError = true)
    }
  }

  /*
   * Does a paginated API request and parses the json output into a sequence of classes
   */
  def executePaginated[T](request: Request[Seq[T]])(implicit reader: Reads[T]): RequestResponse[Seq[T]] = {
    get(request.url) match {
      case Right(json) =>
        val nextPage = (json \ "next").asOpt[String]
        val nextRepos = nextPage.map {
          nextUrl =>
            executePaginated(Request(nextUrl, request.classType)).value.getOrElse(Seq())
        }.getOrElse(Seq())

        val values = (json \ "values").asOpt[Seq[T]].getOrElse(Seq())
        RequestResponse(Some(values ++ nextRepos))

      case Left(error) =>
        RequestResponse[Seq[T]](None, error.detail, hasError = true)
    }
  }

  /*
   * Does an API post
   */
  def post[T](request: Request[T], values: JsValue)(implicit reader: Reads[T]): RequestResponse[T] = {
    val client = new NingWSClient(new AsyncHttpClient().getConfig)

    val jpromise = client.url(request.url)
      .sign(requestSigner)
      .withFollowRedirects(follow = true)
      .post(values)
    val result = Await.result(jpromise, requestTimeout)

    val value = if (Seq(HTTPStatusCodes.OK, HTTPStatusCodes.CREATED).contains(result.status)) {
      val body = result.body

      val jsValue = parseJson(body)
      jsValue match {
        case Right(responseObj) =>
          RequestResponse(responseObj.asOpt[T])
        case Left(message) =>
          RequestResponse[T](None, message = message.detail, hasError = true)
      }
    } else {
      RequestResponse[T](None, result.statusText, hasError = true)
    }

    client.close()
    value
  }

  /* copy paste from post ... */
  def delete[T](url: String): RequestResponse[Boolean] = {
    val client = new NingWSClient(new AsyncHttpClient().getConfig)

    val jpromise = client.url(url)
      .sign(requestSigner)
      .withFollowRedirects(follow = true)
      .delete()
    val result = Await.result(jpromise, requestTimeout)

    val value = if (Seq(HTTPStatusCodes.OK, HTTPStatusCodes.CREATED, HTTPStatusCodes.NO_CONTENT).contains(result.status)) {
      RequestResponse(Option(true))
    } else {
      RequestResponse[Boolean](None, result.statusText, hasError = true)
    }

    client.close()
    value
  }

  private def get(url: String): Either[ResponseError, JsValue] = {
    val client = new NingWSClient(new AsyncHttpClient().getConfig)

    val jpromise = client.url(url)
      .sign(requestSigner)
      .withFollowRedirects(follow = true)
      .get()
    val result = Await.result(jpromise, requestTimeout)

    val value = if (Seq(HTTPStatusCodes.OK, HTTPStatusCodes.CREATED).contains(result.status)) {
      val body = result.body
      parseJson(body)
    } else {
      Left(ResponseError(java.util.UUID.randomUUID().toString, result.statusText, result.statusText))
    }

    client.close()
    value
  }

  private def parseJson(input: String): Either[ResponseError, JsValue] = {
    val json = Json.parse(input)

    val errorOpt = (json \ "error").asOpt[ResponseError]

    errorOpt.map {
      error =>
        Left(error)
    }.getOrElse(Right(json))
  }
}
