package ch.lkmc.blipbird.di

import android.content.Context
import ch.lkmc.blipbird.core.data.BackgroundRefreshController
import ch.lkmc.blipbird.core.data.NotificationSink
import ch.lkmc.blipbird.core.data.ProviderKeyProvider
import ch.lkmc.blipbird.core.database.OpsDatabase
import ch.lkmc.blipbird.core.database.QuotaLedgerDao
import ch.lkmc.blipbird.core.database.ReferenceDao
import ch.lkmc.blipbird.core.database.UserDatabase
import ch.lkmc.blipbird.core.datastore.ProviderKeyStore
import ch.lkmc.blipbird.core.network.AdsbApi
import ch.lkmc.blipbird.core.network.AeroApi
import ch.lkmc.blipbird.core.network.AeroDataBoxApi
import ch.lkmc.blipbird.core.network.AviationWeatherApi
import ch.lkmc.blipbird.core.network.OpenMeteoApi
import ch.lkmc.blipbird.platform.NotificationEmitter
import ch.lkmc.blipbird.platform.WorkManagerRefreshController
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Provides @Singleton
    fun okHttp(@ApplicationContext context: Context): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024))
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Blipbird/0.1 (+https://github.com/L-K-M/Blipbird)")
                .apply {
                    if (chain.request().url.host == AeroDataBoxApi.RAPIDAPI_HOST) {
                        header("X-RapidAPI-Host", AeroDataBoxApi.RAPIDAPI_HOST)
                    }
                }
                .build()
            chain.proceed(request)
        }
        .build()

    private fun retrofit(client: OkHttpClient, baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton
    fun aeroDataBoxApi(client: OkHttpClient): AeroDataBoxApi =
        retrofit(client, AeroDataBoxApi.BASE_URL).create(AeroDataBoxApi::class.java)

    @Provides @Singleton
    fun aeroApi(client: OkHttpClient): AeroApi =
        retrofit(client, AeroApi.BASE_URL).create(AeroApi::class.java)

    @Provides @Singleton
    fun adsbApi(client: OkHttpClient): AdsbApi =
        retrofit(client, "https://api.adsb.lol/").create(AdsbApi::class.java)

    @Provides @Singleton
    fun aviationWeatherApi(client: OkHttpClient): AviationWeatherApi =
        retrofit(client, AviationWeatherApi.BASE_URL).create(AviationWeatherApi::class.java)

    @Provides @Singleton
    fun openMeteoApi(client: OkHttpClient): OpenMeteoApi =
        retrofit(client, OpenMeteoApi.BASE_URL).create(OpenMeteoApi::class.java)

    @Provides @Singleton
    fun userDb(@ApplicationContext context: Context): UserDatabase = UserDatabase.build(context)

    @Provides @Singleton
    fun opsDb(@ApplicationContext context: Context): OpsDatabase = OpsDatabase.build(context)

    @Provides
    fun referenceDao(ops: OpsDatabase): ReferenceDao = ops.referenceDao()

    @Provides
    fun quotaDao(ops: OpsDatabase): QuotaLedgerDao = ops.quotaLedgerDao()
}

/** Bridges the DataStore key store to the narrow provider-facing interface. */
@Singleton
class KeyProviderImpl @Inject constructor(
    private val store: ProviderKeyStore,
) : ProviderKeyProvider {
    override suspend fun aeroDataBoxKey(): String? = store.keys.first().aeroDataBoxKey
    override suspend fun aeroApiKey(): String? = store.keys.first().aeroApiKey
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindsModule {
    @Binds abstract fun keyProvider(impl: KeyProviderImpl): ProviderKeyProvider
    @Binds abstract fun notificationSink(impl: NotificationEmitter): NotificationSink
    @Binds abstract fun backgroundRefresh(impl: WorkManagerRefreshController): BackgroundRefreshController
}
