package tsec.csrf

import java.security.MessageDigest
import java.time.Clock

import cats.data.OptionT
import cats.effect.Sync
import tsec.common.ByteEV
import tsec.mac.imports.{JCAMacPure, MacTag}
import tsec.common._
import tsec.mac._
import tsec.mac.imports._
import cats.syntax.all._

/** A CSRF Token Signer, adapted from:
  * https://github.com/playframework/playframework/blob/master/framework/src/play/src/main/scala/play/api/libs/crypto/CSRFTokenSigner.scala
  * by Will Sargent, to use the tsec crypto primitives
  *
  * Also very similar to cryptobits, but with a variable argument mac signer, thus we have more than
  * one mac algorithm to choose from
  *
  */
final case class CSRFTokenSigner[F[_]: Sync, A: MacTag: ByteEV](
    key: MacSigningKey[A],
    tokenLength: Int = 16,
    clock: Clock = Clock.systemUTC()
)(
    implicit mac: JCAMacPure[F, A]
) {

  def isEqual(s1: String, s2: String): Boolean =
    MessageDigest.isEqual(s1.utf8Bytes, s2.utf8Bytes)

  def signToken(string: String): F[CSRFToken] = {
    val joined = string + "-" + clock.millis()
    mac.sign(joined.utf8Bytes, key).map(s => CSRFToken(joined + "-" + s.asByteArray.toUtf8String))
  }

  def generateNewToken: F[CSRFToken] =
    signToken(CSRFToken.generateHexBase(tokenLength))

  /**
    * Extract a signed token
    */
  def extractRaw(token: CSRFToken): OptionT[F, String] =
    token.split("-", 3) match {
      case Array(raw, nonce, signed) =>
        OptionT(
          mac
            .sign((raw + "-" + nonce).utf8Bytes, key)
            .map(f => if (MessageDigest.isEqual(f.asByteArray, signed.utf8Bytes)) Some(raw) else None)
        )
      case _ =>
        OptionT.none
    }

  def checkEqual(token1: CSRFToken, token2: CSRFToken): F[Boolean] =
    (for {
      raw1 <- extractRaw(token1)
      raw2 <- extractRaw(token2)
    } yield isEqual(raw1, raw2)).getOrElse(false)

}
