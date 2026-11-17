package app.lianxu.independent

/** Close the actual system picker in existing draft/layout regression tests. */
internal fun cancelNativeImagePicker(){
    val instrument=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    val deadline=android.os.SystemClock.uptimeMillis()+5000
    while(android.os.SystemClock.uptimeMillis()<deadline&&instrument.uiAutomation.rootInActiveWindow?.packageName?.toString()==instrument.targetContext.packageName)android.os.SystemClock.sleep(50)
    check(instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)){"无法取消系统图片选择器"}
    instrument.waitForIdleSync()
}
