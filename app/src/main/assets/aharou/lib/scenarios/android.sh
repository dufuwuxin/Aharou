#!/bin/sh
# 场景：Kotlin / Android 开发（JDK 17 + Android SDK 36 + build-tools 35.0.0）
# 依赖 lib/android-sdk.sh 提供的共享安装逻辑。
scenario_name_android() { echo "Kotlin / Android 开发（JDK + Android SDK）"; }

install_scenario_android() {
    if [ "$PMGR" != "apt" ] && [ "$PMGR" != "xbps" ]; then
        echo "${C_YELLOW}注意${C_RESET}：编译 Android 应用建议使用 Debian / Ubuntu 镜像，内置 Alpine（musl）需额外处理。"
        printf "当前包管理器为 %s，仍要继续吗？[y/N]: " "${PMGR:-未知}"
        read a
        case "$a" in y|Y) ;; *) return 2 ;; esac
    fi
    echo ""
    echo "安装 JDK 与基础工具..."
    [ "$PMGR" != xbps ] || pkg_update || return 1
    jdk_pkg=$(android_jdk_pkg) || return 1
    if [ "$PMGR" = xbps ]; then
        java_pkg="$jdk_pkg"
        echo "Android SDK：平台 36，build-tools 35.0.0，ARM64 原生工具。"
        show_plan "java" || return 2
        pkg_add $(base_pkgs) "$jdk_pkg" unzip || return 1
    else
        pkg_add "$jdk_pkg" curl wget unzip || return 1
    fi
    install_android_sdk "36" "35.0.0" || return 1
    install_android_tools_arm64 || return 1
    write_android_profile || return 1
    echo ""
    echo "${C_GREEN}Android / Kotlin 环境就绪：$HOME/android/sdk${C_RESET}"
    echo "  新开终端会自动带上 JAVA_HOME / ANDROID_HOME；编译步骤见「进阶教程 · 在容器中编译 Android 应用」。"
    return 0
}