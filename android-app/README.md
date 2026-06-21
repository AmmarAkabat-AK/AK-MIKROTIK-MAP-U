# AK MikroTik Agent — تطبيق Android (Kotlin)

تطبيق أندرويد يكرّر وظائف الوكيل `ak-agent.mjs` بالكامل، لكنه يعمل كتطبيق أصلي
يبقى نشطاً في الخلفية عبر **Foreground Service**.

## ماذا يفعل؟

- يتصل بالسيرفر عبر **WebSocket**:
  `wss://ak-mikrotik-map-u.replit.app/api/agent/ws`
  (قابل للتغيير من داخل التطبيق) مع **إعادة اتصال تلقائية كل 5 ثوانٍ**.
- عند الاتصال يرسل `{"type":"register","subnets":[...]}` ويستقبل `registered`.
- ينفّذ أوامر MikroTik عبر **بروتوكول RouterOS API الثنائي** (المنفذ 8728)
  ويعيد النتيجة بصيغة `{"type":"response","reqId":...,"data":...}`.
- يشغّل **خادم HTTP محلي** على `127.0.0.1:7779` (للمتصفح على نفس الجهاز)
  مع المسارات: `/health`, `/check-ip`, `/ping-devices`, وكل الأوامر.
- **يبدأ تلقائياً عند إقلاع الجهاز** (اختياري عبر مفتاح في الواجهة).

### الأوامر المدعومة (مطابقة للوكيل الأصلي)
`ping` · `um-users` · `discover` · `interfaces` · `ip-scan` · `vlan-stats`
· `arp` · `backup-config` · `backup-um` · `active-count` · `reboot`

## بنية المشروع

```
android-app/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/app/ak/mikrotik/agent/
        │   ├── MainActivity.kt        ← الواجهة العربية
        │   ├── AgentService.kt        ← الخدمة الأمامية + WebSocket
        │   ├── AgentWebSocket          (داخل AgentService)
        │   ├── HttpAgentServer.kt     ← خادم 127.0.0.1:7779
        │   ├── CommandExecutor.kt     ← تنفيذ أوامر MikroTik
        │   ├── RouterOSApi.kt         ← بروتوكول RouterOS API الثنائي
        │   ├── BootReceiver.kt        ← التشغيل عند الإقلاع
        │   ├── NetUtils.kt            ← الشبكات الفرعية + ICMP/TCP
        │   ├── Prefs.kt               ← الإعدادات المحفوظة
        │   └── AgentState.kt          ← الحالة المشتركة مع الواجهة
        └── res/ (layout, values, drawable)
```

## خطوات البناء (Android Studio)

1. افتح **Android Studio** (أحدث إصدار، Giraffe أو أعلى).
2. اختر **File → New → Import Project** ثم اختر مجلد `android-app`.
   - أو **Open** ثم وجّهه إلى المجلد مباشرة.
3. عند أول فتح، Android Studio سيُنشئ **Gradle Wrapper** تلقائياً.
   - إن لم يحدث، شغّل من الطرفية داخل المجلد:
     `gradle wrapper --gradle-version 8.7`
4. اترك Gradle يُنزّل التبعيات (OkHttp وغيرها).
5. وصّل هاتفك (مع تفعيل **USB Debugging**) أو شغّل محاكياً.
6. اضغط **Run ▶** لتثبيت التطبيق، أو
   **Build → Build Bundle(s)/APK(s) → Build APK(s)** لإنتاج ملف APK.
   - ملف الإخراج: `app/build/outputs/apk/debug/app-debug.apk`

### لإصدار APK موقّع (للتوزيع)
**Build → Generate Signed Bundle / APK → APK** ثم أنشئ keystore واتبع الخطوات.

## ملاحظات تشغيل مهمة

- **تعطيل تحسين البطارية**: من إعدادات النظام، اسمح للتطبيق بالعمل دون قيود
  (Battery → Unrestricted) حتى لا يوقفه النظام في الخلفية.
- **أذونات الإشعارات** (أندرويد 13+): يطلبها التطبيق عند أول تشغيل، وهي مطلوبة
  لإظهار إشعار الخدمة الأمامية.
- **الهاتف والراوترات على نفس الشبكة**: ليصل التطبيق إلى منفذ RouterOS API (8728)
  يجب أن يكون الهاتف قادراً على الوصول لعناوين الراوترات (نفس الشبكة أو VPN).
- **منفذ RouterOS API**: تأكد أن `api` مفعّل على الراوتر:
  `/ip service enable api` (المنفذ الافتراضي 8728).

## تغيير عنوان السيرفر

غيّره من حقل **عنوان السيرفر** في الواجهة قبل الضغط على «تشغيل الوكيل»،
أو عدّل القيمة الافتراضية في `Prefs.kt` (الثابت `DEFAULT_URL`).
