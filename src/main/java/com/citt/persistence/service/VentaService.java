package com.citt.persistence.service;

import java.util.List;

import com.citt.exceptions.VentaNotFoundException;
import com.citt.persistence.entity.Venta;

public interface VentaService {
    List<Venta> findAllVentas();
    Venta saveVenta(Venta venta);
    Venta updateVenta(Long idVenta, Venta venta) throws VentaNotFoundException;
    void deleteVenta(Long idVenta) throws VentaNotFoundException;
    Venta findById(Long idVenta) throws VentaNotFoundException;
}
