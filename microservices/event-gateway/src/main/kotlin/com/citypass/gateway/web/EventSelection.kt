package com.citypass.gateway.web

/**
 * Elige qué eventos de los leídos del bus devolver, y en qué orden.
 *
 * Vive aparte del controller porque el controller abre un `KafkaConsumer` y por eso queda
 * fuera de la medición de cobertura. Esta parte —la que decide qué ve cada usuario— sí se
 * prueba: es donde puede colarse un evento ajeno.
 */
object EventSelection {

    /**
     * Eventos publicados por [source], del más reciente al más antiguo.
     *
     * Los eventos llegan desordenados porque se leen de varios tópicos a la vez y cada uno
     * tiene su propio offset; el orden se recompone por `metadata.receivedAt`, que lo pone
     * el gateway con un único reloj.
     *
     * Un evento sin `metadata` o sin `source` no se devuelve. No debería existir —el
     * gateway siempre los estampa— pero si apareciera uno, dejarlo pasar sería mostrarle a
     * alguien un evento que no se puede atribuir.
     *
     * @param eventos Envelopes deserializados, tal como salieron del bus.
     * @param source Valor de `metadata.source` que se busca: el `sub` del token.
     * @param limit Cantidad máxima a devolver.
     */
    fun propios(eventos: List<Map<String, Any?>>, source: String, limit: Int): List<Map<String, Any?>> =
        eventos
            // La metadata se resuelve una sola vez y se arrastra: volver a buscarla para
            // ordenar dejaría un chequeo de nulo que ya no puede darse después del filtro.
            .mapNotNull { evento -> (evento["metadata"] as? Map<*, *>)?.let { evento to it } }
            .filter { (_, metadata) -> metadata["source"] == source }
            // `receivedAt` es epoch en milisegundos; sin él, el evento va al final.
            .sortedByDescending { (_, metadata) -> (metadata["receivedAt"] as? Number)?.toLong() ?: Long.MIN_VALUE }
            .take(limit)
            .map { (evento, _) -> evento }
}
