package vegabobo.dsusideloader.ui.screens.diaginstallation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class FabAction {
    INSTALL, SAVE_LOGS
}

enum class DiagDialog {
    IS_NOT_SHOWING_ANY_DIALOG,
    EXIT,
    ERROR_EXTERNAL_SDCARD_ALLOC,
    ERROR_NO_AVAIL_STORAGE,
    ERROR_F2FS_WRONG_PATH,
    ERROR_EXTENTS,
    ERROR_SELINUX_A10
}

data class DiagUiState(
    val installationCmd: String = "",
    val installationLogs: String = "",
    val fabAction: FabAction = FabAction.INSTALL,
    val isShowingDialog: DiagDialog = DiagDialog.IS_NOT_SHOWING_ANY_DIALOG,
    val navigateUp: Boolean = false,
)

class DiagnoseInstallationViewModel : ViewModel() {

    val _uiState = MutableStateFlow(DiagUiState())
    val uiState: StateFlow<DiagUiState> = _uiState.asStateFlow()
    var currentLogs = ""
    var ejectSdCard = false

    private var callbackList: CallbackList<String?>? = null

    fun updateInstallationCmd(installationCmd: String) {
        _uiState.update { it.copy(installationCmd = installationCmd) }
    }

    fun onClickStartInstallation() {
        logcatDiagnostic()
        _uiState.update { it.copy(fabAction = FabAction.SAVE_LOGS) }
    }

    fun onClickSaveLogs() {

    }

    fun cleanLogcatAndInstall() {
        currentLogs = "Installation did not started? try long press into \"Save log\" button"
        _uiState.update {
            it.copy(installationLogs = currentLogs)
        }
        var instCmd = uiState.value.installationCmd
        Shell.cmd("logcat -c").exec()
        if (ejectSdCard)
            instCmd = instCmd.replace("umount_sd=false", "umount_sd=true")
        Shell.cmd(instCmd).exec()
    }

    fun logcatDiagnostic() {
        viewModelScope.launch(Dispatchers.IO) {
            cleanLogcatAndInstall()
            callbackList = object : CallbackList<String?>() {
                override fun onAddElement(logLineNullable: String?) {
                    val logLine = logLineNullable.toString()

                    if (!currentLogs.endsWith("\n"))
                        currentLogs = ""

                    currentLogs += "$logLine\n"

                    // When realpath fails with permission denied reason
                    // probably gsid is trying to allocate into external sdcard
                    // however it throws error, likely due selinux denial
                    // E gsid    : realpath failed: /mnt/media_rw/AE5C-6D79/dsu: Permission denied
                    // Solutions -> run app in system-mode or temporary unmount sdcard
                    if (logLine.contains("realpath failed")
                        && logLine.contains("Permission denied")
                    ) {
                        release()
                        throwError(DiagDialog.ERROR_EXTERNAL_SDCARD_ALLOC)
                    }

                    // gsid requires at least 40% of free storage
                    // E gsid    : free space 24% is below the minimum threshold of 40%
                    // Solutions -> delete some stuff
                    if (logLine.contains("is below the minimum threshold of")) {
                        release()
                        throwError(DiagDialog.ERROR_NO_AVAIL_STORAGE)
                    }

                    // Some kernels are registering f2fs as f2fs_dev, throwing error
                    // https://cs.android.com/android/platform/superproject/+/master:system/core/fs_mgr/libfiemap/utility.cpp;l=113
                    // E gsid    : read failed: /sys/fs/f2fs/dm-4/features: No such file or directory
                    // Solutions -> run app in system-mode or fix kernel
                    if (logLine.contains("read failed")
                        && logLine.contains("No such file or directory")
                        && logLine.contains("f2fs")
                    ) {
                        release()
                        throwError(DiagDialog.ERROR_F2FS_WRONG_PATH)
                    }

                    // Android 10 does not set sepolicy rules for gsid
                    // W Binder:10924_1: type=1400 audit(0.0:51): avc: denied { getattr } for path="/dev/block/mmcblk0p42" \
                    // dev="tmpfs" ino=12407 scontext=u:r:gsid:s0 tcontext=u:object_r:mmcblk_device:s0 tclass=blk_file permissive=0
                    // E gsid    : Failed to get stat for block device: /dev/block/mmcblk0p42: Permission denied
                    // Solutions -> run app in system-mode or setenforce 0
                    if (logLine.contains("Failed to get stat for block device")
                        && logLine.contains("Permission denied")
                    ) {
                        release()
                        throwError(DiagDialog.ERROR_SELINUX_A10)
                    }

                    // Android 10 seems to require a high extents value
                    // E gsid : File is too fragmented, needs more than 512 extents.
                    // Solutions -> run app in system-mode (not tested)
                    if (logLine.contains("File is too fragmented")) {
                        release()
                        throwError(DiagDialog.ERROR_EXTENTS)
                    }

                    _uiState.update {
                        it.copy(installationLogs = currentLogs)
                    }
                }
            }
            Shell.cmd("logcat -v tag | grep gsid | grep -v SHELL")
                .to(callbackList)
                .submit {}
        }
    }

    fun throwError(diagError: DiagDialog) {
        _uiState.update { it.copy(isShowingDialog = diagError) }
    }

    fun onClickEjectAndTryAgain() {
        release(true)
        ejectSdCard = true
        _uiState.update {
            it.copy(
                isShowingDialog = DiagDialog.IS_NOT_SHOWING_ANY_DIALOG,
                installationLogs = currentLogs,
                fabAction = FabAction.SAVE_LOGS
            )
        }
        logcatDiagnostic()
    }

    fun onClickCancel() {
        _uiState.update { it.copy(isShowingDialog = DiagDialog.IS_NOT_SHOWING_ANY_DIALOG) }
    }

    fun onClickSetPermissiveAndTryAgain() {
        _uiState.update { it.copy(isShowingDialog = DiagDialog.IS_NOT_SHOWING_ANY_DIALOG) }
        release(true)
        Shell.cmd("setenforce 0").exec()
        logcatDiagnostic()
    }

    fun onClickConfirmBack(navigateUp: Boolean) {
        release()
        _uiState.update {
            it.copy(navigateUp = navigateUp)
        }
    }

    fun release() {
        release(false)
    }

    fun release(cleanLogs: Boolean) {
        _uiState.update {
            if (cleanLogs)
                currentLogs = ""
            it.copy(fabAction = FabAction.INSTALL, installationLogs = currentLogs)
        }
        callbackList = null
        try {
            Shell.getShell().close()
        } catch (ignored: IOException) {
        }
    }

    fun onLongClickResetInstall() {
        release(true)
    }

}
