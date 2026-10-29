package com.tubo.mcserver

import java.io.File
import java.util.Properties

object InstanceManager {
    fun root(): File = Storage.servers()

    fun list(): List<Instance> {
        val out = ArrayList<Instance>()
        root().listFiles()?.filter { it.isDirectory }?.forEach { d ->
            val p = File(d, "instance.prop")
            if (p.exists()) {
                val pr = Properties()
                p.inputStream().use { pr.load(it) }
                out += Instance(
                    name = pr.getProperty("name", d.name),
                    mcVersion = pr.getProperty("mcVersion", "?"),
                    loader = pr.getProperty("loader", "VANILLA")
                )
            }
        }
        return out.sortedBy { it.name }
    }

    fun create(name: String, mcVersion: String, loader: String): Instance {
        val inst = Instance(name, mcVersion, loader)
        inst.dir().mkdirs()
        inst.modsDir(); inst.pluginsDir()
        val p = File(inst.dir(), "instance.prop")
        p.outputStream().use {
            Properties().apply {
                setProperty("name", name)
                setProperty("mcVersion", mcVersion)
                setProperty("loader", loader)
            }.store(it, null)
        }
        return inst
    }

    fun get(name: String): Instance? = list().firstOrNull { it.name == name }

    fun delete(inst: Instance) {
        inst.dir().deleteRecursively()
    }
}
