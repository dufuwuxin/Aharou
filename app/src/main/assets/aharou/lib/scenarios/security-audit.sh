#!/bin/sh
# Only provision analysis tools; never open or execute a user-provided sample.
scenario_name_security_audit() { echo "授权离线逆向分析（基础工具 + 可选 jadx / apktool）"; }

security_audit_detect() {
    for audit_cmd in readelf objdump strings file java python3 jadx apktool; do
        if command -v "$audit_cmd" >/dev/null 2>&1; then
            printf '  · %s：可用\n' "$audit_cmd"
        else
            printf '  · %s：不可用（未安装）\n' "$audit_cmd"
        fi
    done
}

security_audit_package() {
    case "$PMGR:$1" in
        apk:java) echo openjdk17-jre-headless ;;
        apt:java) echo default-jre-headless ;;
        dnf:java|yum:java) echo java-17-openjdk-headless ;;
        pacman:java) echo jre17-openjdk ;;
        xbps:java) echo openjdk17-jre ;;
        pacman:python3) echo python ;;
        apk:python3|apt:python3|dnf:python3|yum:python3|xbps:python3) echo python3 ;;
        *:readelf|*:objdump|*:strings) echo binutils ;;
        *:file|*:jadx|*:apktool) echo "$1" ;;
        *) return 1 ;;
    esac
}

# Query only the configured repositories for the current architecture. No extra
# repositories, release downloads, pip wrappers, or unverified archives are used.
security_audit_package_available() (
    export LC_ALL=C
    case "$PMGR" in
        apk) apk search -e "$1" 2>/dev/null | grep -q "^$1-[0-9]" ;;
        apt) apt-cache policy "$1" 2>/dev/null | grep -q '^[[:space:]]*Candidate: [0-9]' ;;
        dnf) dnf -q list --available "$1" >/dev/null 2>&1 ;;
        yum) yum -q list available "$1" >/dev/null 2>&1 ;;
        pacman) pacman -Si "$1" >/dev/null 2>&1 ;;
        xbps) xbps-query -R "$1" >/dev/null 2>&1 ;;
        *) return 1 ;;
    esac
)

install_scenario_security_audit() (
    case "$PMGR" in
        apk|apt|dnf|yum|pacman|xbps) ;;
        *) echo "离线分析环境不可用：未支持的包管理器。" >&2; return 1 ;;
    esac
    echo "仅安装授权离线分析工具；不会读取、解压或执行样本，不安装 Ghidra。"
    security_audit_detect
    audit_packages=""
    audit_required=""
    for audit_cmd in readelf objdump strings file java python3; do
        command -v "$audit_cmd" >/dev/null 2>&1 && continue
        audit_pkg=$(security_audit_package "$audit_cmd") || return 1
        case " $audit_packages " in
            *" $audit_pkg "*) ;;
            *) audit_packages="${audit_packages:+$audit_packages }$audit_pkg" ;;
        esac
    done
    echo "基础工具补装清单：${audit_packages:-无（已可用）}"
    printf '输入 y 确认，其它键返回菜单: '
    read audit_answer || return 2
    case "$audit_answer" in y|Y) ;; *) return 2 ;; esac
    for audit_cmd in jadx apktool; do
        command -v "$audit_cmd" >/dev/null 2>&1 && continue
        printf '按需安装 %s？默认跳过 [y/N]: ' "$audit_cmd"
        read audit_answer || return 2
        case "$audit_answer" in
            y|Y)
                audit_pkg=$(security_audit_package "$audit_cmd") || return 1
                audit_packages="${audit_packages:+$audit_packages }$audit_pkg"
                audit_required="${audit_required:+$audit_required }$audit_cmd"
                ;;
        esac
    done
    if [ -n "$audit_packages" ]; then
        pkg_update || { audit_rc=$?; echo "更新软件包列表失败，未安装离线分析工具。" >&2; return "$audit_rc"; }
        for audit_pkg in $audit_packages; do
            if ! security_audit_package_available "$audit_pkg"; then
                echo "不可用：当前仓库/架构未提供 $audit_pkg（或仓库查询失败）；未开始安装，不自动添加软件源或下载。" >&2
                return 1
            fi
        done
        pkg_add $audit_packages || { audit_rc=$?; echo "离线分析工具安装失败，请检查包管理器输出。" >&2; return "$audit_rc"; }
    fi
    audit_missing=""
    for audit_cmd in readelf objdump strings file java python3 $audit_required; do
        command -v "$audit_cmd" >/dev/null 2>&1 || audit_missing="$audit_missing $audit_cmd"
    done
    security_audit_detect
    if [ -n "$audit_missing" ]; then
        echo "离线分析环境未就绪，缺少命令：$audit_missing" >&2
        return 1
    fi
    echo "基础离线分析工具已就绪；标为不可用的可选工具仍未安装。"
)
