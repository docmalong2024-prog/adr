package com.mmo.notilistener

data class NotificationLog(
    val id: String,
    val packageName: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    var status: String, // "SUCCESS", "RETRYING", "FAILED"
    var httpCode: Int = 0,
    var serverResponse: String = ""
)

