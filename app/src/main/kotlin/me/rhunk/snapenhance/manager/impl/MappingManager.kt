package me.rhunk.snapenhance.manager.impl

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import me.rhunk.snapenhance.Constants
import me.rhunk.snapenhance.Logger
import me.rhunk.snapenhance.ModContext
import me.rhunk.snapenhance.bridge.types.BridgeFileType
import me.rhunk.snapenhance.manager.Manager
import me.rhunk.snapenhance.mapper.Mapper
import me.rhunk.snapenhance.mapper.impl.*
import me.rhunk.snapenhance.ui.ViewAppearanceHelper
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

@Suppress("UNCHECKED_CAST")
class MappingManager(private val context: ModContext) : Manager {
    private val mappers = arrayOf(
        BCryptClassMapper::class,
        CallbackMapper::class,
        DefaultMediaItemMapper::class,
        MediaQualityLevelProviderMapper::class,
        EnumMapper::class,
        OperaPageViewControllerMapper::class,
        PlusSubscriptionMapper::class,
        ScCameraSettingsMapper::class,
        StoryBoostStateMapper::class,
        FriendsFeedEventDispatcherMapper::class,
        CompositeConfigurationProviderMapper::class,
        ScoreUpdateMapper::class
    )

    private val mappings = ConcurrentHashMap<String, Any>()
    val areMappingsLoaded: Boolean
        get() = mappings.isNotEmpty()
    private var snapBuildNumber = 0

    @Suppress("deprecation")
    override fun init() {
        val currentBuildNumber = context.androidContext.packageManager.getPackageInfo(
            Constants.SNAPCHAT_PACKAGE_NAME,
            0
        ).longVersionCode.toInt()
        snapBuildNumber = currentBuildNumber

        if (context.bridgeClient.isFileExists(BridgeFileType.MAPPINGS)) {
            runCatching {
                loadCached()
            }.onFailure {
                context.crash("Failed to load cached mappings ${it.message}", it)
            }

            if (snapBuildNumber != currentBuildNumber) {
                context.bridgeClient.deleteFile(BridgeFileType.MAPPINGS)
                context.softRestartApp()
            }
            return
        }
        context.runOnUiThread {
            val statusDialogBuilder = runCatching {
                context.mainActivity?.let {
                    ViewAppearanceHelper.newAlertDialogBuilder(it)
                        .setMessage("Generating mappings, please wait...")
                        .setCancelable(false)
                        .setView(android.widget.ProgressBar(it).apply {
                            setPadding(0, 20, 0, 20)
                        })
                }
            }.getOrNull() ?: null.also {
                context.longToast("Generating mappings, please wait...")
            }

            val loadingDialog = statusDialogBuilder?.show()

            context.executeAsync {
                runCatching {
                    refresh()
                }.onSuccess {
                    context.shortToast("Generated mappings for build $snapBuildNumber")
                    context.softRestartApp()
                }.onFailure {
                    context.longToast("Failed to generate mappings: $it")
                    Logger.error("Failed to generate mappings", it)
                    context.runOnUiThread {
                        loadingDialog?.dismiss()
                        statusDialogBuilder?.apply {
                            setView(null)
                            setMessage("Failed to generate mappings: $it")
                            setNegativeButton("Close") { _, _ ->
                                this@MappingManager.context.mainActivity?.finish()
                            }
                            show()
                        }
                    }
                }
            }
        }
    }

    private fun loadCached() {
        if (!context.bridgeClient.isFileExists(BridgeFileType.MAPPINGS)) {
            Logger.xposedLog("Mappings file does not exist")
            return
        }
        val mappingsObject = JsonParser.parseString(
            String(
                context.bridgeClient.readFile(BridgeFileType.MAPPINGS),
                StandardCharsets.UTF_8
            )
        ).asJsonObject.also {
            snapBuildNumber = it["snap_build_number"].asInt
        }

        mappingsObject.entrySet().forEach { (key, value): Map.Entry<String, JsonElement> ->
            if (value.isJsonArray) {
                mappings[key] = context.gson.fromJson(value, ArrayList::class.java)
                return@forEach
            }
            if (value.isJsonObject) {
                mappings[key] = context.gson.fromJson(value, ConcurrentHashMap::class.java)
                return@forEach
            }
            mappings[key] = value.asString
        }
    }

    @Suppress("DEPRECATION")
    private fun refresh() {
        val mapper = Mapper(*mappers)

        runCatching {
            mapper.loadApk(context.androidContext.packageManager.getApplicationInfo(
                Constants.SNAPCHAT_PACKAGE_NAME,
                0
            ).sourceDir)
        }.onFailure {
            throw Exception("Failed to load APK", it)
        }

        measureTimeMillis {
            val result = mapper.start().apply {
                addProperty("snap_build_number", snapBuildNumber)
            }
            context.bridgeClient.writeFile(BridgeFileType.MAPPINGS, result.toString().toByteArray())
        }.also {
            Logger.xposedLog("Generated mappings in $it ms")
        }
    }

    fun getMappedObject(key: String): Any {
        if (mappings.containsKey(key)) {
            return mappings[key]!!
        }
        throw Exception("No mapping found for $key")
    }

    fun getMappedObjectNullable(key: String): Any? {
        return mappings[key]
    }

    fun getMappedClass(className: String): Class<*> {
        return context.androidContext.classLoader.loadClass(getMappedObject(className) as String)
    }

    fun getMappedClass(key: String, subKey: String): Class<*> {
        return context.androidContext.classLoader.loadClass(getMappedValue(key, subKey))
    }

    fun getMappedValue(key: String): String {
        return getMappedObject(key) as String
    }

    fun <T : Any> getMappedList(key: String): List<T> {
        return listOf(getMappedObject(key) as List<T>).flatten()
    }

    fun getMappedValue(key: String, subKey: String): String {
        return getMappedMap(key)[subKey] as String
    }

    fun getMappedMap(key: String): Map<String, *> {
        return getMappedObject(key) as Map<String, *>
    }
}