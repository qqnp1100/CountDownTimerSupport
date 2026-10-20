# CountDownTimerSupport
Android倒计时器，支持开始倒计时、暂停倒计时、继续倒计时、停止倒计时、重新倒计时

## 项目构建

项目使用 Android Gradle Plugin 9.3.2、Gradle 9.7.1，`compileSdk` 和 `targetSdk` 为 36，需要 Android SDK Platform 36 和 Build Tools 36.0.0。Gradle Daemon 使用 `gradle/gradle-daemon-jvm.properties` 指定的 JDK 25。通过 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` 指定 Android SDK 路径。

```bash
./gradlew :example:assembleDebug :count-down-timer:assembleRelease
```

示例保留 Support Library 28，因此在 `gradle.properties` 中设置了 `android.useAndroidX=false` 和 `android.uniquePackageNames=false`（旧版矢量图依赖共用 namespace）。前者会产生 AGP 弃用提示，后者保留依赖 namespace 重复的警告。库的最低 Android 版本为 API 14，Java 源码和字节码版本为 Java 8。

库通过 `maven-publish` 发布，包含 AAR、源码和 Javadoc，坐标仍为 `in.xiandan:count-down-timer:1.0.3`。发布到本地 Maven 仓库：

```bash
./gradlew :count-down-timer:publishToMavenLocal
```

## 回归测试

```bash
./gradlew :count-down-timer:testDebugUnitTest -Pandroid.useAndroidX=true
```

Robolectric 的测试依赖需要 AndroidX，因此仅在测试命令中启用。测试覆盖并发停止、自动结束与手动停止竞争、暂停恢复、重置重启及过期回调。

计时器的状态切换使用同一把锁保护。暂停、停止、重置或重新启动后，旧轮次尚未派发的回调会被忽略；`onTick()` 使用对应任务执行时的剩余时间快照。

## 示例APK
[example-1.0.3.apk](example-1.0.3.apk)

## 效果演示
![](screenshot/count-down-timer.gif)
![](screenshot/count-down-timer-2.gif)

## 快速开始
### Android Studio - 在build.gradle中引入
```java
implementation 'in.xiandan:count-down-timer:1.0.3'
```

### 旧版请谨慎升级到1.0.3以上
> 1.启用了新的包名，升级后需要在用到的地方修改为新的包名
> 
> 2.onFinish的回调时机调整为与[CountDownTimer#onFinish()](https://developer.android.com/reference/android/os/CountDownTimer#onFinish())相同

#### 初始化
```
//总时长 间隔时间
CountDownTimerSupport mTimer = new CountDownTimerSupport(millisInFuture, countDownInterval);
// SimpleOnCountDownTimerListener
mTimer.setOnCountDownTimerListener(new OnCountDownTimerListener() {
    @Override
    public void onTick(long millisUntilFinished) {
        // 倒计时间隔
    }

    @Override
    public void onFinish() {
        // 倒计时结束
    }

    @Override
    public void onCancel() {
        // 倒计时手动停止
    }
});
```

#### 启动倒计时
```
mTimer.start();
```

#### 暂停倒计时
```
mTimer.pause();
```

#### 恢复倒计时
```
mTimer.resume();
```

#### 停止倒计时
```
mTimer.stop();
```

#### 重置并启动倒计时
```
mTimer.reset();
mTimer.start();
```
