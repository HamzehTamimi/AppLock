package xyz.mufanc.applock.core.scope

import io.github.libxposed.api.XposedInterface
import xyz.mufanc.applock.core.scope.provider.ScopeProvider
import xyz.mufanc.applock.core.util.Log

object ScopeManager {

    private const val TAG = "ScopeManager"

    private val scope = mutableSetOf<String>()
    private val availableProviders = mutableListOf<String>()

    @Synchronized
    private fun updateScope(old: Set<String>, new: Set<String>, from: String? = null) {
        scope.removeAll(old)
        scope.addAll(new)

        val source = from ?: "unknown source"
        Log.i(TAG, "update scope from $source: { ${scope.joinToString(", ")} }")
    }

    fun init(ixp: XposedInterface) {
        val providers = ScopeProvider::class.sealedSubclasses
        val disabledProviders = ixp.getRemotePreferences("applock_disabled_providers").all.keys

        Log.d(TAG, "scope providers: ${providers.joinToString(", ") { "${it.simpleName}" }}")

        providers.forEach { klass ->
            val provider = klass.objectInstance!!

            try {
                if (!provider.isAvailable()) return@forEach
            } catch (err: Throwable) {
                Log.e(TAG, "failed to check availability for scope provider: ${klass.simpleName}", err)
                return@forEach
            }

            availableProviders.add(klass.simpleName!!)
            if (klass.simpleName in disabledProviders) return@forEach

            try {
                provider.registerOnScopeChangedListener(
                    object : ScopeProvider.OnScopeChangedListener {
                        override fun onScopeChanged(old: Set<String>, new: Set<String>) {
                            updateScope(old, new, klass.simpleName)
                        }
                    }
                )

                Log.i(TAG, "initializing scope provider: ${klass.simpleName}")
                provider.init(ixp)
            } catch (err: Throwable) {
                Log.e(TAG, "failed to initialize scope provider: ${klass.simpleName}", err)
            }
        }
    }

    fun query(pkg: String): Boolean {
        return scope.contains(pkg)
    }

    fun getAvailableProviders(): List<String> {
        return availableProviders
    }
}
