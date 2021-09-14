package com.dounine.douyinpay.tools.util

import org.apache.commons.codec.binary.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.security.{SecureRandom, Security}
import javax.crypto.{Cipher, KeyGenerator}
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

object DecodeUtil {

  def decryptData(data: String, key: String): Try[String] = {
    Try {
      val keyGen = KeyGenerator.getInstance("AES")
      keyGen.init(128, new SecureRandom(key.getBytes()))
      Security.addProvider(new BouncyCastleProvider())
      val cipher = Cipher.getInstance("AES/ECB/PKCS7Padding", "BC")
      val skeySpec = new SecretKeySpec(key.getBytes(), "AES")
      cipher.init(Cipher.DECRYPT_MODE, skeySpec)
      val encrypted1 =
        Base64.decodeBase64(data)
      val original = cipher.doFinal(encrypted1)
      new String(original, "UTF-8")
    }
  }

}
