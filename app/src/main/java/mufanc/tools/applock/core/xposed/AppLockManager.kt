package mufanc.tools.applock.core.xposed

import android.app.ActivityThread
import android.content.Context
import android.net.Uri
import android.os.*
import androidx.core.os.bundleOf
import mufanc.easyhook.api.EasyHook
import mufanc.easyhook.api.Logger
import mufanc.easyhook.api.hook.hook
import mufanc.easyhook.api.reflect.getStaticFieldAs
import mufanc.tools.applock.BuildConfig
import mufanc.tools.applock.IAppLockManager
import mufanc.tools.applock.MyApplication
import mufanc.tools.applock.util.update

class AppLockManager private constructor() : IAppLockManager.Stub() {

    companion object {
        private val TRANSACTION_CODE = "Lock"
            .toByteArray()
            .mapIndexed { i, ch -> ch.toInt() shl (i * 8) }
            .sum()

        enum class BundleKeys {
            PID, UID, VERSION
        }

        private val instance by lazy { AppLockManager() }
        fun query(packageName: String): Boolean {
            return instance.whitelist.contains(packageName)
        }

        private val context by lazy {
            @Suppress("Cast_Never_Succeeds")
            ActivityThread.currentActivityThread().systemContext as Context
        }

        fun init() = EasyHook.handle {  // Hook `onTransact()` 以便与模块通信
            onLoadPackage("android") {
                findClass("miui.process.ProcessManagerNative").hook {
                    method({ name == "onTransact" }) { method ->
                        Logger.i("@Hooker: hook onTransact: $method")
                        before { param ->
                            if (param.args[0] != TRANSACTION_CODE) return@before
                            if (context.packageManager.getNameForUid(Binder.getCallingUid()) != BuildConfig.APPLICATION_ID) return@before
                            (param.args[2] as Parcel).writeStrongBinder(instance)
                            param.result = true
                        }
                    }
                }

                findClass("com.android.server.SystemServiceManager").hook {
                    method({ name == "startBootPhase" }) { method ->
                        Logger.i("@Hooker: hook startBootPhase: $method")

                        val index = method.parameterTypes.indexOf(Int::class.java)
                        val code = findClass("com.android.server.SystemService")
                            .getStaticFieldAs<Int>("PHASE_BOOT_COMPLETED")

                        after { param ->
                            if (param.args[index] == code) {
                                context.contentResolver.acquireUnstableContentProviderClient(
                                    Uri.parse("content://${BuildConfig.APPLICATION_ID}.provider")
                                )?.call(
                                    "scope", null, Bundle()
                                )?.getStringArray("scope")?.also {
                                    instance.whitelist.addAll(it)
                                    Logger.i("@Server: load scope from provider: ${it.contentToString()}")
                                } ?: let {
                                    Logger.e("@Server: failed to resolve whitelist!")
                                }
                            }
                        }
                    }
                }
            }
        }

        val client by lazy {
            MyApplication.processManager?.let {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    if (it.transact(TRANSACTION_CODE, data, reply, 0)) {
                        return@lazy asInterface(reply.readStrongBinder())
                    }
                } finally {
                    data.recycle()
                    reply.recycle()
                }
                return@lazy null
            }
        }
    }

    private val whitelist = mutableSetOf<String>()

    override fun handshake(): Bundle {
        Logger.i("@Server: handshake from client!")
        return bundleOf(
            BundleKeys.PID.name to Process.myPid(),
            BundleKeys.UID.name to Process.myUid(),
            BundleKeys.VERSION.name to BuildConfig.VERSION_CODE
        )
    }

    override fun reboot() {
        Binder.restoreCallingIdentity(
            Binder.clearCallingIdentity().also {
                IPowerManager.Stub.asInterface(
                    ServiceManager.getService(Context.POWER_SERVICE)
                ).reboot(false, null, false)
            }
        )
    }

    override fun updateWhitelist(packageList: Array<out String>) {
        whitelist.update(packageList.toList())
        Logger.i("@Server: scope updated: ${packageList.contentToString()}")
    }
}
