# Конфиденциальность / Privacy — OvertakeLink

[English below](#english)

OvertakeLink (`io.github.queryparams.overtakelink`) **не собирает, не хранит у себя на серверах и никому
не передаёт** данные пользователя. У проекта нет серверов, аналитики и рекламы.

## Разрешения и для чего они

- **Микрофон.** Используется, только когда Android Auto открывает голосовой ввод (Ассистент,
  ответ на сообщение). Звук передаётся в Android Auto на этом же телефоне и не сохраняется.
- **Уведомления.** Значок работы в шторке и кнопка «Стоп».
- **Местоположение.** OvertakeLink его не запрашивает: навигация Android Auto использует GPS телефона
  напрямую. Встроенный приёмник Android Auto (из Open Headunit) умеет передавать координаты в
  Android Auto, если вы сами выдадите разрешение в его расширенных настройках; координаты при этом
  не сохраняются и никуда не отправляются.
- **USB и Bluetooth.** Связь с головным устройством машины и кнопки руля.
- **Установка приложений.** Только для обновления самого OvertakeLink по кнопке «Скачать и
  установить»; Android спрашивает это разрешение отдельно.

## Журнал работы

OvertakeLink ведёт журнал на телефоне (`Загрузки/OvertakeLink`), чтобы можно было разобраться с
проблемой. В нём есть модель телефона, служебные сообщения головного устройства и, среди них,
**Bluetooth-адрес машины**. Журнал никуда не отправляется сам — только если вы приложите его
к сообщению. Перед публикацией в открытом доступе (например, в Issues на GitHub) адрес можно
удалить из файла.

## Сеть

- Android Auto и OvertakeLink общаются внутри телефона (localhost).
- Для проверки обновлений OvertakeLink при открытии (не чаще раза в 10 минут) и по кнопке «Проверить обновления» обращается к GitHub (`api.github.com`). При этом
  GitHub, как любой сайт, видит IP-адрес телефона; других данных не передаётся.
- По кнопке «Скачать и установить» файл новой версии скачивается с GitHub.
- Данные, которые обрабатывает сам Android Auto, регулируются политикой Google.

## Вопросы

[Issues проекта на GitHub](https://github.com/queryParams/OvertakeLink/issues).

---

## English

OvertakeLink (`io.github.queryparams.overtakelink`) **does not collect, store on its own servers or
share** user data. The project has no servers, analytics or ads.

### Permissions and what they are for

- **Microphone.** Used only when Android Auto opens voice input (the Assistant, replying to a
  message). The audio goes to Android Auto on the same phone and is not stored.
- **Notifications.** The status icon and the “Stop” button.
- **Location.** OvertakeLink does not request it: Android Auto navigation uses the phone’s GPS
  directly. The built-in Android Auto receiver (from Open Headunit) can pass coordinates to Android
  Auto if you grant the permission yourself in its advanced settings; the coordinates are not
  stored or sent anywhere.
- **USB and Bluetooth.** Connection to the car’s head unit and steering wheel buttons.
- **Installing apps.** Only to update OvertakeLink itself with the “Download and install” button;
  Android asks for this permission separately.

### Log

OvertakeLink keeps a log on the phone (`Downloads/OvertakeLink`) to help troubleshoot problems. It
contains the phone model, head unit service messages and, among them, **the car’s Bluetooth
address**. The log is never sent by itself — only if you attach it to a report. Before publishing it
(for example, in GitHub Issues) you can remove the address from the file.

### Network

- Android Auto and OvertakeLink talk inside the phone (localhost).
- To check for updates, OvertakeLink contacts GitHub (`api.github.com`) when opened (at most once every 10 minutes) and with the “Check for updates” button. Like any
  website, GitHub sees the phone’s IP address; no other data is sent.
- With the “Download and install” button, the new version’s file is downloaded from GitHub.
- Data processed by Android Auto itself is governed by Google’s policy.

### Questions

[Project Issues on GitHub](https://github.com/queryParams/OvertakeLink/issues).
