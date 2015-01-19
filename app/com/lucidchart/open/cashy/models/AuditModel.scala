package com.lucidchart.open.cashy.models

import com.lucidchart.open.relate.SqlResult
import com.lucidchart.open.relate.interp._
import java.util.Date
import play.api.db._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.Play.{configuration, current}

object AuditType extends Enumeration {
  val upload  = Value(0, "UPLOAD")
  val delete  = Value(1, "DELETE")
}

case class AuditEntry(
  id: Long,
  userId: Long,
  data: String,
  auditType: AuditType.Value,
  created: Date
)

case class AssetAuditData(
  bucket: String,
  assetKey: String,
  cloudfrontUrl: String,
  gzipped: Boolean
)
object AssetAuditData {
  implicit val uploadDataReads: Reads[AssetAuditData] = (
    (JsPath \ "bucket").read[String] and
    (JsPath \ "assetKey").read[String] and
    (JsPath \ "cloudfrontUrl").read[String] and
    (JsPath \ "gzipped").read[Boolean])(AssetAuditData.apply _)

  implicit val uploadDataWrites: Writes[AssetAuditData] = (
    (JsPath \ "bucket").write[String] and
    (JsPath \ "assetKey").write[String] and
    (JsPath \ "cloudfrontUrl").write[String] and
    (JsPath \ "gzipped").write[Boolean])(unlift(AssetAuditData.unapply))
}

case class AuditPage(
  current: Int,
  max: Int,
  audits: List[AuditWithUser]
)

case class AuditWithUser(
  user: String,
  data: String,
  auditType: AuditType.Value,
  created: Date
)

object AuditModel extends AuditModel
class AuditModel {
  private val pageSize = configuration.getInt("audit.max").get
  private val auditWithUserParser = { row: SqlResult =>
    AuditWithUser(
      row.string("email"),
      row.string("data"),
      AuditType(row.int("type")),
      row.date("created")
    )
  }

  private def findById(auditId: Long): Option[AuditEntry] = {
    DB.withConnection { implicit connection =>
      sql"""SELECT `id`, `user_id`, `data`, `type`, `created`
        FROM `audits`
        WHERE `id` = $auditId""".asSingleOption { row =>
          AuditEntry(row.long("id"), row.long("user_id"), row.string("data"), AuditType(row.int("type")), row.date("created"))
      }
    }
  }

  private def createAuditEntry(userId: Long, data: String, assetType: AuditType.Value) {
    val now = new Date()
    DB.withConnection { implicit connection =>
      val auditId = sql"""INSERT INTO `audits`
        (`user_id`, `data`, `type`, `created`)
        VALUES ($userId, $data, ${assetType.id}, $now)""".executeInsertLong()
    }
  }

  def createUploadAudit(userId: Long, bucket: String, assetKey: String, cloudfrontUrl: String, gzipped: Boolean) {
    val uploadData = Json.stringify(Json.toJson(AssetAuditData(bucket, assetKey, cloudfrontUrl, gzipped)))
    createAuditEntry(userId, uploadData, AuditType.upload)
  }

  def createDeleteAudit(userId: Long, bucket: String, assetKey: String, cloudfrontUrl: String, gzipped: Boolean) {
    val deleteData = Json.stringify(Json.toJson(AssetAuditData(bucket, assetKey, cloudfrontUrl, gzipped)))
    createAuditEntry(userId, deleteData, AuditType.delete)
  }

  /**
   * Get a page of AuditWithUser case classes.
   *
   * @param page the page number to get
   * @return a page of AuditWithUser
   */
  def getAuditPage(page: Int) = {
    DB.withConnection { implicit connection =>
      val audits = sql"""
        SELECT `user_id`, `email`, `data`, `type`, `created`
        FROM `users` INNER JOIN (
          SELECT `user_id`, `data`, `type`, `created`
          FROM `audits`
          ORDER BY `created` DESC
          LIMIT $pageSize
          OFFSET ${(page - 1) * pageSize}
        ) as a on users.id = a.user_id
      """.asList(auditWithUserParser)

      val maxCount = sql"""
        SELECT COUNT(1) FROM `audits`
      """.asScalar[Long]

      AuditPage(
        page,
        (maxCount / pageSize + (if (maxCount % pageSize != 0) 1 else 0)).asInstanceOf[Int],
        audits
      )
    }
  }

}