package com.dounine.douyinpay.model.models

object PayUserInfoModel {

  final case class Info(
      nickName: String,
      id: String,
      avatar: String
  ) extends BaseSerializer

  final case class DouYinSearchAvatarThumb(
      avg_color: String,
      height: Long,
      image_type: Int,
      is_animated: Boolean,
      open_web_url: String,
      uri: String,
      url_list: Seq[String],
      width: Long
  )

  final case class DouYinSearchOpenInfo(
      avatar_thumb: DouYinSearchAvatarThumb,
      nick_name: String,
      search_id: String
  )

  final case class DouYinSearchData(
      open_info: Seq[DouYinSearchOpenInfo]
  )

  final case class DouYinSearchResponse(
      status_code: Int,
      data: DouYinSearchData
  )

  final case class KuaishouSearchResponse(
      result: Int,
      error_msg: Option[String],
      headUrl: Option[String],
      userName: Option[String],
      userId: Option[String]
  )

  final case class KuaiShouResponse(
      result: Int,
      error_msg: Option[String],
      headUrl: Option[String],
      userId: Option[Long],
      userName: Option[String]
  )

}
