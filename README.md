# Mercurius

Mercurius 
![Screenshot](Mercurius.png)


WIP
Mercurius es un programa de facturacion e inventarios disenado para supermercados.

Desarrollado en:
MYSQL
HTML
PRIMEFACES
JakartaEE 10
Jakarta Faces 4.0
Java 11


El programa esta estructurado de la siguiente forma:
Web Pages - Contiene todas las paginas desplegables.
Source - Contiene todo el codigo relevante para la ejecucion de la aplicacion.

La aplicacion se maneja con 3 packages.
----------------------------
Controller | Model | Service
----------------------------

1. El usuario usa el controller para manipular el Model
2. El Model meneja la Data y Logica
3. El Service se encarga de brindar y actualizar la Data del Model.

Practicamente usamos el patron MVC donde nuestro view son las paginas XHTML y el resto es igual.
![MVC](https://user-images.githubusercontent.com/22756354/168221640-35f1916d-96bb-4d09-b805-89bb118bb176.png)
