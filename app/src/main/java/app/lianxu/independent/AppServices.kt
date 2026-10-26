package app.lianxu.independent

/** Production defaults. Instrumentation supplies isolated local mocks before Activity creation. */
internal object AppServices {
    @Volatile var createTextClient:()->TextChatClient={HttpsTextChatClient()}
    @Volatile var databaseName="lianxu.db"
    @Volatile var vaultNamespace="model-configs"
}
