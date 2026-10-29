package com.tubo.mcserver

import java.io.File

data class Instance(
    val name: String,
    val mcVersion: String,
    val loader: String   // VANILLA / FABRIC / BEDROCK
) {
    fun dir(): File = File(Storage.servers(), name)
    fun isBedrock(): Boolean = loader == "BEDROCK"
    fun serverJar(): File = when (loader) {
        "FABRIC" -> File(dir(), "fabric-server-launch.jar")
        "BEDROCK" -> File(dir(), "bedrock_server")
        else -> File(dir(), "server.jar")
    }
    fun modsDir(): File = File(dir(), "mods").apply { mkdirs() }
    fun pluginsDir(): File = File(dir(), "plugins").apply { mkdirs() }
    fun eulaFile(): File = File(dir(), "eula.txt")
}
