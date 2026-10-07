# Mobile pairing contract

Мобильное приложение использует только `https://marscore.ru/` и существующий `PairingRepository`.

- `POST /api/v1/auth/device-pairings` создаёт код; `poll_secret` никогда не показывается.
- Браузер открывается на `/pair`; browser-сессия в приложение не передаётся.
- Статус опрашивается до `expires_in`; credential запрашивается ровно одним exchange.
- Credential сохраняется только через `AndroidKeyStoreCredentialStore`; отзыв очищает хранилище только после успешного ответа.
- Нативный чат пока не реализован и экран его не обещает.
