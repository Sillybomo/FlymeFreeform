package io.github.mangi.flymefreeform.hook

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 验证旧桌面触摸服务优先及其缺失时的新类回退，不触及事件拦截策略。
 *
 * @author bomo
 */
class LauncherTouchServiceClassResolverTest {
    @Test
    fun prefersLegacyServiceWhenBothNamesExist() {
        val loader = MappingClassLoader(
            mapOf(
                NEW_SERVICE_CLASS to NewServiceForTest::class.java,
                LEGACY_SERVICE_CLASS to LegacyServiceForTest::class.java,
            ),
        )

        assertSame(LegacyServiceForTest::class.java, loadTouchServiceClass(loader))
    }

    @Test
    fun fallsBackToNewServiceWhenLegacyNameIsAbsent() {
        val loader = MappingClassLoader(
            mapOf(NEW_SERVICE_CLASS to NewServiceForTest::class.java),
        )

        assertSame(NewServiceForTest::class.java, loadTouchServiceClass(loader))
    }

    private class MappingClassLoader(
        private val mappedClasses: Map<String, Class<*>>,
    ) : ClassLoader(LauncherTouchServiceClassResolverTest::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            mappedClasses[name] ?: if (name == NEW_SERVICE_CLASS || name == LEGACY_SERVICE_CLASS) {
                throw ClassNotFoundException(name)
            } else {
                super.loadClass(name, resolve)
            }
    }

    private class NewServiceForTest

    private class LegacyServiceForTest

    private companion object {
        const val NEW_SERVICE_CLASS = "com.android.quickstep.TouchInteractionService"
        const val LEGACY_SERVICE_CLASS = "com.android.quickstep.OplusBaseTouchInteractionService"
    }
}
