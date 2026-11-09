-- Datos inventados. Se cargan cada vez que arranca el contenedor, porque parar la base vacía su tmpfs.
CREATE TABLE pedidos (id SERIAL PRIMARY KEY, producto TEXT NOT NULL, cantidad INT NOT NULL);
INSERT INTO pedidos (producto, cantidad) VALUES ('Café en grano 1 kg', 3), ('Molinillo manual', 1), ('Filtros de papel', 12), ('Taza de cerámica', 4);
