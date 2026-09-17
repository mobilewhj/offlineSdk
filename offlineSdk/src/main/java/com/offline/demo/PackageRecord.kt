package com.offline.demo

/** 与存储方式无关的离线包元数据：整数版本与 ZIP 整包 SHA-256，持久化由调用方负责。 */
data class PackageRecord(val version: Int, val sha256: String)
