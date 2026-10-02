package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;

/** Economic activity in Central Market, independent of the NPC Broker. */
public class CentralMarketSimulationConfig {
	@Property(key="gameserver.central.market.simulation.enabled", defaultValue="true")
	public static boolean ENABLED;
	@Property(key="gameserver.central.market.simulation.population", defaultValue="3000")
	public static int POPULATION;
	@Property(key="gameserver.central.market.simulation.tick_seconds", defaultValue="5")
	public static int TICK_SECONDS;
	@Property(key="gameserver.central.market.simulation.catalog_batch", defaultValue="2000")
	public static int CATALOG_BATCH;
	@Property(key="gameserver.central.market.simulation.refresh_min_seconds", defaultValue="120")
	public static int REFRESH_MIN_SECONDS;
	@Property(key="gameserver.central.market.simulation.refresh_max_seconds", defaultValue="480")
	public static int REFRESH_MAX_SECONDS;
	@Property(key="gameserver.central.market.simulation.max_stack", defaultValue="100")
	public static int MAX_STACK;
}
