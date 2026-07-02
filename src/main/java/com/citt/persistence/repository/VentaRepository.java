package com.citt.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.citt.persistence.entity.Venta;

public interface VentaRepository extends JpaRepository<Venta, Long> {
}
