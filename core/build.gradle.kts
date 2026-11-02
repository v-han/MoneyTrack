plugins { kotlin("jvm") }

// 纯 Kotlin 模块：解析规则可以在电脑上测试，不需要启动 Android 模拟器。
kotlin { jvmToolchain(17) }

dependencies { testImplementation("junit:junit:4.13.2") }
