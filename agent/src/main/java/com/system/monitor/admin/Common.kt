package com.system.monitor.admin

import com.google.gson.Gson

data class Agent(
    val id: String,
    val hostname: String,
    val os: String,
    val arch: String,
    val androidVersion: String,
    val sdk: Int,
    val rooted: Boolean,
    val lastSeen: String
)

fun Any.toJson(): String = Gson().toJson(this)