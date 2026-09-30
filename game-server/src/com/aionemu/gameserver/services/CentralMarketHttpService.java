package com.aionemu.gameserver.services;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.alibaba.fastjson2.JSON;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.configs.main.GSConfig;
import com.aionemu.gameserver.utils.ThreadPoolManager;
import com.aionemu.gameserver.world.World;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Small ES5 browser API. Every mutation requires a character-bound request receipt. */
public final class CentralMarketHttpService {
	private static final Logger log=LoggerFactory.getLogger(CentralMarketHttpService.class);
	private static final Path MEDIA=Path.of("config/central-market/media");
	private static final Map<String,Form> FORMS=new ConcurrentHashMap<>();
	private static final SecureRandom RANDOM=new SecureRandom();
	private static final Map<Integer,String> ICONS=new ConcurrentHashMap<>();
	private static HttpServer server;
	public static synchronized void start() throws Exception {
		if (!GSConfig.ENABLE_CENTRAL_MARKET) return;
		CentralMarketService.start();
		server=HttpServer.create(new InetSocketAddress(GSConfig.CENTRAL_MARKET_BIND,GSConfig.CENTRAL_MARKET_PORT),16);
		server.createContext("/market",CentralMarketHttpService::handle);
		server.setExecutor(ThreadPoolManager.getInstance());
		server.start();
		log.info("Central Market listening at http://{}:{}/market",GSConfig.CENTRAL_MARKET_BIND,GSConfig.CENTRAL_MARKET_PORT);
	}
	public static synchronized void stop() {
		if(server!=null) { server.stop(1); server=null; }
	}
	private static Player findPlayer(String sessionId) {
		if(sessionId.isBlank()) return null;
		for(Player player:World.getInstance().getAllPlayers()) {
			String token=player.getAccount().getSecurityToken();
			if(token.isEmpty()) continue;
			String prefix=java.util.HexFormat.of().formatHex(token.substring(0,Math.min(16,token.length())).getBytes(StandardCharsets.US_ASCII));
			if(MessageDigest.isEqual(sessionId.getBytes(StandardCharsets.US_ASCII),token.getBytes(StandardCharsets.US_ASCII))
				|| MessageDigest.isEqual(sessionId.getBytes(StandardCharsets.US_ASCII),prefix.getBytes(StandardCharsets.US_ASCII))) return player;
		}
		return null;
	}
	static void loadIcons() throws IOException {
		ICONS.clear();
		Set<String> files=new HashSet<>();
		for(String line:Files.readAllLines(MEDIA.resolve("icon_sources.tsv"))) {
			String[] fields=line.split("\t");
			if(fields.length>=2 && fields[0].matches("[0-9]{9}") && fields[1].matches("[a-z0-9_-]+\\.png")) {
				ICONS.put(Integer.parseInt(fields[0]),fields[1]);
				files.add(fields[1]);
			}
		}
		long missing=files.stream().filter(file->!Files.isRegularFile(MEDIA.resolve("icons").resolve(file))).count();
		if(missing>0) throw new IOException("Central Market requires extracted client icons: "+missing+" files missing");
	}
	static boolean hasClientItem(int item) { return ICONS.containsKey(item); }
	private record Form(Player player,Object connection,long expires) {}
	private CentralMarketHttpService() {}

	public static void handle(HttpExchange x) throws IOException {
		try {
			x.getResponseHeaders().set("Cache-Control","no-store");
			x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
			x.getResponseHeaders().set("Referrer-Policy","same-origin");
			String path=x.getRequestURI().getPath(), method=x.getRequestMethod();
			if (!method.equals("GET")&&!method.equals("POST")){send(x,405,"application/json","{\"error\":\"Method not allowed.\"}");return;}
			if (method.equals("GET") && path.equals("/market")) { send(x,200,"text/html",Files.readString(MEDIA.resolve("market.html")));return; }
			if (method.equals("GET") && path.startsWith("/market/media/")) {
				String file=path.substring("/market/media/".length());
				if(!file.matches("market\\.(css|js)|icons/[0-9]{9}\\.png")){send(x,404,"text/plain","Not found");return;}
				if(file.startsWith("icons/")) {
					// Extracted client artwork is static; rebuilding a row must not download it again.
					x.getResponseHeaders().set("Cache-Control","public, max-age=86400");
					String icon=ICONS.get(Integer.parseInt(file.substring(6,15)));
					if(icon==null){send(x,404,"text/plain","Item has no client icon");return;}
					file="icons/"+icon;
				}
				Path asset=MEDIA.resolve(file); if(!Files.isRegularFile(asset)){send(x,404,"text/plain","Not found");return;}
				byte[] data=Files.readAllBytes(asset);x.getResponseHeaders().set("Content-Type",file.endsWith(".png")?"image/png":file.endsWith(".js")?"application/javascript; charset=utf-8":"text/css; charset=utf-8");
				x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);return;
			}
			if (!path.equals("/market/state") && !path.equals("/market/action")){send(x,404,"application/json","{\"error\":\"Not found.\"}");return;}
			Map<String,String> args=parse(method.equals("POST")?new String(x.getRequestBody().readNBytes(16385),StandardCharsets.UTF_8):x.getRequestURI().getRawQuery());
			Player p=findPlayer(args.getOrDefault("session_id",args.getOrDefault("token","")));
			if(p==null||!p.isOnline()){send(x,403,"application/json","{\"error\":\"Log in, then reopen Warehouse.\"}");return;}
			String notice="";
			if(path.equals("/market/action")) {
				if(!method.equals("POST")){send(x,405,"application/json","{\"error\":\"Use POST for warehouse actions.\"}");return;}
				String origin=x.getRequestHeaders().getFirst("Origin"),expected="http://"+x.getRequestHeaders().getFirst("Host");
				if(origin!=null&&!origin.equals(expected))throw new IllegalArgumentException("Reopen Warehouse to continue.");
				String id=args.getOrDefault("request",""); Form form=FORMS.get(id);
				if(form==null||form.player()!=p||form.connection()!=p.getClientConnection()||form.expires()<System.currentTimeMillis()) {
					send(x,403,"application/json","{\"error\":\"This form expired. Refresh Warehouse.\"}");return;
				}
				notice=CentralMarketService.action(p,args,id);
			} else if(!method.equals("GET")){send(x,405,"application/json","{\"error\":\"Use GET for market data.\"}");return;}
			long readStarted=System.nanoTime();
			Map<String,Object> state=CentralMarketService.snapshot(p,args);
			x.getResponseHeaders().set("Server-Timing","market;dur="+(System.nanoTime()-readStarted)/1_000_000);
			long now=System.currentTimeMillis();FORMS.entrySet().removeIf(e->e.getValue().expires()<now);
			if(FORMS.size()>5000)throw new IllegalStateException("Market is busy. Try again.");
			byte[] random=new byte[24];RANDOM.nextBytes(random);String id=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
			FORMS.put(id,new Form(p,p.getClientConnection(),now+600000));state.put("request",id);state.put("notice",notice);
			send(x,200,"application/json",JSON.toJSONString(state));
		} catch(IllegalArgumentException e){send(x,400,"application/json",JSON.toJSONString(Map.of("error",e.getMessage())));}
		catch(Exception e){log.error("Central Market request failed",e);send(x,503,"application/json","{\"error\":\"Central Market is unavailable. Refresh before trying again.\"}");}
		finally{x.close();}
	}
	private static Map<String,String> parse(String text) {
		if(text==null)return new HashMap<>();if(text.length()>16384)throw new IllegalArgumentException("Request is too large.");
		Map<String,String> args=new HashMap<>();for(String part:text.split("&")){String[] kv=part.split("=",2);if(kv.length==2)args.put(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}return args;
	}
	private static void send(HttpExchange x,int status,String type,String body) throws IOException {
		byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);
	}
}
