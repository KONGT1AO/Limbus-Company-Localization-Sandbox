// 根构建配置：项目仅维护统一的主版宿主包名。
plugins {
    alias(libs.plugins.android.application) apply false
    id("com.android.library") version "8.5.2" apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

extra["VERSION"] = "2.3.2-limbus"
extra["VERSION_CODE"] = 23202
extra["PACKAGE_NAME_32BIT"] = "io.limbuspe.box"
