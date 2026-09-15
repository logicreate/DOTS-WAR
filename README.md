# Dots War — сборка и публикация в Google Play

## Версия 2.0 — что изменилось в обёртке

* Игра открывается через `WebViewAssetLoader` с адреса `https://appassets.androidplatform.net/assets/index.html`
  (а не `file://`). Это даёт странице настоящий origin: работают Web Worker (движок ИИ считает в фоне),
  `localStorage` (прогресс, настройки), `history` (аппаратная кнопка «Назад» возвращает в меню
  и спрашивает подтверждение в живой партии) и Firebase без смешанного контента.
* Добавлена зависимость `androidx.webkit:webkit` (см. `app/build.gradle`) и разрешение `VIBRATE`
  (вибрация при захвате, отключается в настройках игры).
* Экран не гаснет во время партии (`FLAG_KEEP_SCREEN_ON`), системный масштаб шрифта игнорируется.
* `versionCode 2`, `versionName 2.0.0` — при загрузке в Play Console каждая новая сборка должна иметь
  больший `versionCode`.


## Что в этом проекте

Android-приложение — WebView-обёртка вокруг игры (`assets/index.html`).
Сборка настроена на:

- **compileSdk / targetSdk 36** (Android 16) — обязательное требование
  Google Play для новых загрузок и обновлений с 31 августа 2026 года.
- **AGP 9.3.0 / Gradle 9.5.0** — актуальные стабильные версии, совместимые
  с compileSdk 36.
- Сборку через **GitHub Actions** (в песочнице, где я работаю, нет доступа
  к Android SDK/Gradle-серверам, поэтому сборка идёт на серверах GitHub —
  бесплатно и без Android Studio на вашем компьютере).

Workflow (`.github/workflows/build-apk.yml`) собирает два файла:

1. **`app-debug.apk`** — неподписанный, для быстрой проверки на своём
   телефоне (просто установить).
2. **`app-release.aab`** — то, что нужно грузить в Play Console. Собирается
   только если в репозитории настроены секреты подписи (см. ниже) — без
   них шаг сборки .aab просто пропускается.

## Шаг 1 — настроить подпись (один раз)

Я сгенерировал релизный ключ (`dotswar-release.keystore`) — он идёт
отдельным файлом, **не кладите его в репозиторий**, он нужен только в
GitHub Secrets. Потеряете — потеряете возможность выпускать обновления
под тем же приложением, храните в надёжном месте (менеджер паролей,
облако с доступом только вам).

В репозитории на GitHub: **Settings → Secrets and variables → Actions →
New repository secret**, добавьте четыре секрета:

| Имя | Значение |
|---|---|
| `KEYSTORE_BASE64` | содержимое файла `dotswar-release.keystore.base64` (одной строкой) |
| `KEYSTORE_PASSWORD` | пароль из файла `keystore_password.txt` |
| `KEY_ALIAS` | `dotswar` |
| `KEY_PASSWORD` | тот же пароль, что и `KEYSTORE_PASSWORD` |

После этого при следующем push сборка автоматически создаст ещё и
`app-release.aab` в Artifacts — это и есть файл для Play Console.

## Шаг 2 — политика конфиденциальности

В репозитории лежит готовый `privacy-policy.html`. Google Play требует
публичную ссылку на неё. Проще всего — включить GitHub Pages:
**Settings → Pages → Source: Deploy from a branch → Branch: main, папка
`/ (root)` → Save**. Через минуту-две страница будет доступна по адресу
вида `https://<ваш-логин>.github.io/<репозиторий>/privacy-policy.html` —
эту ссылку вставите в Play Console на шаге App content.

## Шаг 3 — публикация в Play Console

1. play.google.com/console → **Create app**, укажите название, язык,
   платно/бесплатно, категория — Games.
2. **Store listing**: короткое и полное описание, иконка 512×512
   (можно взять `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` и
   увеличить/пересобрать под 512×512), скриншоты с телефона (2–8 штук),
   feature graphic 1024×500.
3. **App content**: ссылка на privacy policy из шага 2, анкета возрастного
   рейтинга, целевая аудитория, Data safety (честно: собираются только
   имя игрока и ID комнаты в онлайн-режиме, см. текст политики), декларация
   об отсутствии рекламы.
4. **Testing → Closed testing**: для нового аккаунта разработчика это
   обязательный шаг — минимум 12 тестировщиков, тестирующих 14 дней подряд,
   прежде чем откроется Production. Создайте трек, пригласите тестировщиков
   по email-списку или ссылке, загрузите `app-release.aab` туда.
5. Через 14 дней активного тестирования появится доступ к **Production** —
   загружаете тот же (или обновлённый) .aab уже в боевой релиз и
   отправляете на модерацию.

## Структура проекта

```
DotsWar/
├── build.gradle                          — AGP 9.3.0
├── settings.gradle
├── gradle.properties
├── privacy-policy.html                   — для GitHub Pages
├── .github/workflows/build-apk.yml       — debug apk + подписанный release aab
└── app/
    ├── build.gradle                      — compileSdk/targetSdk 36, signing config
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/dotswar/app/MainActivity.java
        ├── assets/index.html             — сама игра
        └── res/mipmap-*/ic_launcher*.png
```

## Локальная сборка (Android Studio, без GitHub)

Тот же проект открывается через Open → выбрать папку `DotsWar`. Для
release-сборки экспортируйте те же четыре переменные окружения
(`KEYSTORE_PATH` — путь к файлу `dotswar-release.keystore` на диске,
остальные три как в таблице выше) перед запуском Gradle, либо соберите
`assembleDebug`/`bundleDebug` без них.


## Версия 2.2 — вход через Google (аккаунты)

Игра использует Firebase Authentication: гость входит анонимно автоматически, прогресс хранится в облаке
под его UID. Кнопка «G Google» в настройках привязывает Google-аккаунт (прогресс переносится на любое устройство).

Внутри приложения Google запрещает OAuth в WebView, поэтому вход делается нативно через Credential Manager
и токен передаётся в игру (`AndroidBridge.googleSignIn()` → `onGoogleIdToken(token)`). Чтобы это заработало:

1. Firebase Console → Authentication → Sign-in method: включите **Anonymous**, **Google** и **Email/Password**
   (последний нужен для «кода переноса»).
2. Firebase Console → Project settings → Your apps → добавьте Android-приложение `com.dotswar.app`
   и укажите **SHA-1 и SHA-256** отпечатки обоих ключей (debug.keystore из репозитория и release/upload-ключа;
   для Play App Signing — ещё и отпечаток ключа подписи из Play Console → App integrity).
   Команда: `keytool -list -v -keystore debug.keystore -alias androiddebugkey -storepass android`.
3. Там же скопируйте **Web client ID** (Authentication → Sign-in method → Google → Web SDK configuration)
   и вставьте его в `MainActivity.java` в константу `WEB_CLIENT_ID`.
4. Опубликуйте обновлённые правила базы (`firebase-rules.json`) — теперь запись профиля разрешена только владельцу.

Без шага 3 кнопка Google в приложении покажет «Не удалось войти», всё остальное работает как прежде.
