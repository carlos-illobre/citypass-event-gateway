package com.citypass.gateway.integration

import com.citypass.gateway.controller.SubscriptionController
import com.citypass.gateway.model.Subscription
import com.citypass.gateway.service.CallbackUrlValidator
import com.citypass.gateway.service.SubscriptionService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.time.Instant

@Tag("integration")
class SubscriptionControllerIntegrationTest {

    private val OWNER = "com.citypass.movilidad"

    private val subscriptionService: SubscriptionService = mock()

    // Acepta cualquier destino: acá se prueba el ruteo, no la validación.
    private val callbackUrlValidator: CallbackUrlValidator = mock()

    private lateinit var mockMvc: MockMvc

    /**
     * Inyecta un token de grupo en los endpoints, que ahora lo exigen.
     *
     * En producción lo arma el resource server a partir del header Authorization; acá
     * se resuelve directo porque lo que se prueba es el ruteo, no la autenticación.
     */
    private val tokenDePrueba = object : HandlerMethodArgumentResolver {
        override fun supportsParameter(parameter: MethodParameter) =
            Jwt::class.java.isAssignableFrom(parameter.parameterType)

        override fun resolveArgument(
            parameter: MethodParameter,
            mavContainer: ModelAndViewContainer?,
            webRequest: NativeWebRequest,
            binderFactory: WebDataBinderFactory?
        ): Any = Jwt.withTokenValue("test")
            .header("alg", "RS256")
            .subject("usuario1")
            .claim("namespace", OWNER)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build()
    }

    @BeforeEach
    fun setUp() {
        val controller = SubscriptionController(subscriptionService, callbackUrlValidator, mock())
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(tokenDePrueba)
            .build()
    }

    @Test
    fun `GET subscriptions returns empty array`() {
        whenever(subscriptionService.getAll(OWNER)).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/subscriptions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    fun `POST subscriptions registers new webhook`() {
        val sub = Subscription(
            id = "sub-123", topic = "movilidad.bici", callbackUrl = "http://localhost/hook",
            owner = OWNER, createdBy = "usuario1"
        )
        whenever(subscriptionService.register("movilidad.bici", "http://localhost/hook", OWNER, "usuario1")).thenReturn(Result.success(sub))

        mockMvc.perform(
            post("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"topic": "movilidad.bici", "callbackUrl": "http://localhost/hook"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("sub-123"))
            .andExpect(jsonPath("$.topic").value("movilidad.bici"))
    }

    @Test
    fun `DELETE subscriptions returns 204 on success`() {
        whenever(subscriptionService.unregister("sub-123", OWNER)).thenReturn(true)

        mockMvc.perform(delete("/api/v1/subscriptions/sub-123"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE subscriptions returns 404 when not found`() {
        whenever(subscriptionService.unregister("unknown", OWNER)).thenReturn(false)

        mockMvc.perform(delete("/api/v1/subscriptions/unknown"))
            .andExpect(status().isNotFound)
    }
}
