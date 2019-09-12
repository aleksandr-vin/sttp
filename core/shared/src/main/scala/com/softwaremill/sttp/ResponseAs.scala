package com.softwaremill.sttp

import com.softwaremill.sttp.internal.SttpFile

import scala.collection.immutable.Seq
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}

/**
  * @tparam T Target type as which the response will be read.
  * @tparam S If `T` is a stream, the type of the stream. Otherwise, `Nothing`.
  */
sealed trait ResponseAs[T, +S] {
  def map[T2](f: T => T2): ResponseAs[T2, S] = mapWithMetadata { case (t, _) => f(t) }
  def mapWithMetadata[T2](f: (T, ResponseMetadata) => T2): ResponseAs[T2, S] = MappedResponseAs[T, T2, S](this, f)
}

/**
  * Response handling specification which isn't derived from another response
  * handling method, but needs to be handled directly by the backend.
  */
sealed trait BasicResponseAs[T, +S] extends ResponseAs[T, S]

case object IgnoreResponse extends BasicResponseAs[Unit, Nothing]
case object ResponseAsByteArray extends BasicResponseAs[Array[Byte], Nothing]
case class ResponseAsStream[T, S]()(implicit val responseIsStream: S =:= T) extends BasicResponseAs[T, S]
case class ResponseAsFile(output: SttpFile, overwrite: Boolean) extends BasicResponseAs[SttpFile, Nothing]

case class ResponseAsFromMetadata[T, S](f: ResponseMetadata => ResponseAs[T, S]) extends ResponseAs[T, S]

case class MappedResponseAs[T, T2, S](raw: ResponseAs[T, S], g: (T, ResponseMetadata) => T2) extends ResponseAs[T2, S] {
  override def mapWithMetadata[T3](f: (T2, ResponseMetadata) => T3): ResponseAs[T3, S] =
    MappedResponseAs[T, T3, S](raw, (t, h) => f(g(t, h), h))
}

object ResponseAs {
  implicit class RichResponseAsEither[L, R, S](ra: ResponseAs[Either[L, R], S]) {
    def mapRight[R2](f: R => R2): ResponseAs[Either[L, R2], S] = ra.map(_.right.map(f))
  }

  private[sttp] def parseParams(s: String, charset: String): Seq[(String, String)] = {
    s.split("&")
      .toList
      .flatMap(
        kv =>
          kv.split("=", 2) match {
            case Array(k, v) =>
              Some((Rfc3986.decode()(k, charset), Rfc3986.decode()(v, charset)))
            case _ => None
          }
      )
  }

  /**
    * Handles responses according to the given specification when basic
    * response specifications can be handled eagerly, that is without
    * wrapping the result in the target monad (`handleBasic` returns
    * `Try[T]`, not `R[T]`).
    */
  private[sttp] trait EagerResponseHandler[S] {
    def handleBasic[T](bra: BasicResponseAs[T, S]): Try[T]

    def handle[T, R[_]](responseAs: ResponseAs[T, S], responseMonad: MonadError[R], meta: ResponseMetadata): R[T] = {

      responseAs match {
        case MappedResponseAs(raw, g) =>
          responseMonad.map(handle(raw, responseMonad, meta))(t => g(t, meta))
        case ResponseAsFromMetadata(f) => handle(f(meta), responseMonad, meta)
        case bra: BasicResponseAs[T, S] =>
          responseMonad.fromTry(handleBasic(bra))
      }
    }
  }

  /**
    * Tries to deserialize the right component of `base` using the given function. Any exception are represented as
    * a [[DeserializationError]].
    */
  def deserializeCatchingExceptions[T, S](
      base: ResponseAs[Either[String, String], S],
      deserialize: String => T
  ): ResponseAs[Either[ResponseError[Exception], T], S] =
    base
      .map {
        case Left(s) => Left(HttpError(s))
        case Right(s) =>
          Try(deserialize(s)) match {
            case Failure(e: Exception) => Left(DeserializationError(s, e, e.getMessage))
            case Failure(t: Throwable) => throw t
            case Success(b)            => Right(b)
          }
      }
}

sealed abstract class ResponseError[+T] extends Exception
case class HttpError(body: String) extends ResponseError[Nothing]
case class DeserializationError[T](original: String, error: T, message: String) extends ResponseError[T]
