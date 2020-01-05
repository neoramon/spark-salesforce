package com.springml.spark.salesforce

import org.apache.log4j.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SaveMode}
import com.springml.salesforce.wave.api.APIFactory
import com.springml.salesforce.wave.api.BulkAPI
import com.springml.salesforce.wave.util.WaveAPIConstants
import com.springml.salesforce.wave.model.JobInfo


/**
 * Write class responsible for update Salesforce object using data provided in dataframe
 * First column of dataframe contains Salesforce Object
 * Next subsequent columns are fields to be updated
 */
class SFObjectWriter (
    val username: String,
    val password: String,
    val login: String,
    val version: String,
    val sfObject: String,
    val mode: SaveMode,
    val upsert: Boolean,
    val externalIdFieldName: String,
    val csvHeader: String
    ) extends Serializable {

  @transient val logger = Logger.getLogger(classOf[SFObjectWriter])

  def writeData(rdd: RDD[Row], backoffPollingTime: Long, maxWriteRetries: Int): Boolean = {
    val csvRDD = rdd.map(row => row.toSeq.map(value => Utils.rowValue(value)).mkString(","))

    val jobInfo = new JobInfo(WaveAPIConstants.STR_CSV, sfObject, operation(mode, upsert))
    jobInfo.setExternalIdFieldName(externalIdFieldName)

    val jobId = bulkAPI.createJob(jobInfo).getId

    csvRDD.mapPartitionsWithIndex {
      case (index, iterator) => {
        val records = iterator.toArray.mkString("\n")
        var batchInfoId : String = null
        if (records != null && !records.isEmpty()) {
          val data = csvHeader + "\n" + records
          val batchInfo = bulkAPI.addBatch(jobId, data)
          batchInfoId = batchInfo.getId
        }

        val success = (batchInfoId != null)
        // Job status will be checked after completing all batches
        List(success).iterator
      }
    }.reduce((a, b) => a & b)

    bulkAPI.closeJob(jobId)
    var init = 1
    while (init < maxWriteRetries) {
      if (bulkAPI.isCompleted(jobId)) {
        logger.info(s"Job completed, id: $jobId")
        return true
      }

      val waitTime = backoffPollingTime * init
      logger.info(s"Job not completed, id: $jobId, waiting: $waitTime ms")
      Thread.sleep(waitTime)
      init += 1
    }

    logger.info(s"Job not completed, id: $jobId. Timeout..." )
    false
  }

  // Create new instance of BulkAPI every time because Spark workers cannot serialize the object
  private def bulkAPI(): BulkAPI = {
    APIFactory.getInstance().bulkAPI(username, password, login, version)
  }

  private def operation(mode: SaveMode, upsert: Boolean): String = {
    if (upsert) {
      "upsert"
    } else if (mode != null && SaveMode.Overwrite.name().equalsIgnoreCase(mode.name())) {
      WaveAPIConstants.STR_UPDATE
    } else if (mode != null && SaveMode.Append.name().equalsIgnoreCase(mode.name())) {
      WaveAPIConstants.STR_INSERT
    } else {
      logger.warn("SaveMode " + mode + " Not supported. Using 'insert' operation")
      WaveAPIConstants.STR_INSERT
    }
  }

}