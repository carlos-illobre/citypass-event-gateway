const { Kafka } = require('kafkajs');
const { SchemaRegistry } = require('@kafkajs/confluent-schema-registry');

const KAFKA_BROKERS = (process.env.KAFKA_BROKERS || 'localhost:9092').split(',');
const SCHEMA_REGISTRY_URL = process.env.SCHEMA_REGISTRY_URL || 'http://localhost:8081';
const GROUP_ID = process.env.KAFKA_GROUP_ID || 'movilidad-consumer-group';
const TOPICS = (process.env.KAFKA_TOPICS || 'movilidad.bici.devuelta').split(',');

const kafka = new Kafka({
  clientId: 'movilidad-consumer',
  brokers: KAFKA_BROKERS,
  retry: { retries: 10, initialRetryTime: 3000 },
});

const registry = new SchemaRegistry({ host: SCHEMA_REGISTRY_URL });
const consumer = kafka.consumer({ groupId: GROUP_ID });

async function run() {
  await consumer.connect();
  console.log(`Connected to Kafka at ${KAFKA_BROKERS}`);

  await consumer.subscribe({ topics: TOPICS, fromBeginning: true });
  console.log(`Subscribed to topics: ${TOPICS.join(', ')}`);

  await consumer.run({
    eachMessage: async ({ topic, partition, message }) => {
      try {
        const event = await registry.decode(message.value);

        console.log('========== EVENTO RECIBIDO ==========');
        console.log(`Topic:     ${topic}`);
        console.log(`Key:       ${message.key?.toString()}`);
        console.log(`Partition: ${partition}`);
        console.log(`Offset:    ${message.offset}`);
        console.log('Datos:');
        console.log(JSON.stringify(event, null, 2));
        console.log('=====================================');
      } catch (error) {
        console.error(`Error procesando mensaje: ${error.message}`);
      }
    },
  });
}

async function shutdown() {
  console.log('Shutting down...');
  await consumer.disconnect();
  process.exit(0);
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

run().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});
