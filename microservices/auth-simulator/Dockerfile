FROM node:20-alpine
WORKDIR /app
COPY package.json .
RUN npm install --production
COPY src/ src/

# La imagen de Node trae el usuario `node` sin privilegios. Correr como root no aporta
# nada acá —el puerto es alto y no se escribe nada fuera de la imagen— y convierte
# cualquier ejecución de código remota en root dentro del contenedor, que es el primer
# escalón de cualquier fuga hacia el host.
#
# Va después de los COPY: los archivos quedan de root y el proceso sólo los lee, que es
# justo lo que se quiere.
USER node

EXPOSE 8083
CMD ["node", "src/index.js"]
