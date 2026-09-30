package com.aionemu.gameserver.services;

import java.math.BigInteger;
import java.util.*;

/** Pure market arithmetic. Currency never passes through floating point. */
public final class CentralMarketRules {
	public static final long MAX_KINAH = 999_999_999_999_999L;
	public static final long HIGH_VALUE = 20_000_000_000L;
	public static final long REGISTRATION_DELAY = 15 * 60 * 1000L;
	public static final int MAX_ORDERS = 100;
	public static final long BASE_VOLUME = 50_000; // tenths of VT

	private CentralMarketRules() {}

	public static long total(long price, long count) {
		if (price <= 0 || count <= 0 || count > MAX_KINAH / price)
			throw new IllegalArgumentException("Invalid price or quantity.");
		return price * count;
	}

	public static long add(long balance, long amount) {
		if (amount < 0 || balance < 0 || balance > MAX_KINAH - amount)
			throw new IllegalArgumentException("Kinah limit reached.");
		return balance + amount;
	}

	public static long collect(long gross, boolean premium) {
		if (gross < 0 || gross > MAX_KINAH) throw new IllegalArgumentException("Invalid proceeds.");
		return BigInteger.valueOf(gross).multiply(BigInteger.valueOf(premium ? 8450 : 6500))
			.divide(BigInteger.valueOf(10_000)).longValueExact();
	}

	public static List<Long> ladder(long base, long floor, long ceiling) {
		long step = Math.max(1, base / 200); // 0.5% ticks within the published +/-7.5% band
		TreeSet<Long> levels = new TreeSet<>();
		for (int i = -15; i <= 15; i++) levels.add(Math.max(floor, Math.min(ceiling, base + step * i)));
		return List.copyOf(levels);
	}

	public static long adjust(long base, long floor, long ceiling, long buys, long sells) {
		if (buys == sells) return base;
		long step = Math.max(1, base / 100);
		return Math.max(floor, Math.min(ceiling, base + (buys > sells ? step : -step)));
	}

	public static long volume(String category) {
		return switch (category) {
			case "Weapons", "Armor" -> 100;
			case "Accessories" -> 50;
			case "Materials" -> 1;
			default -> 3;
		};
	}
}
