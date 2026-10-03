package com.aionemu.gameserver.services.player;
import java.util.*;
import java.util.concurrent.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.network.aion.AionConnection;
import com.aionemu.gameserver.network.aion.serverpackets.SM_SECURITY_TOKEN;

/** Synthetic accounts and packet buffers only; no players, sockets or database. */
public final class WebSessionCheck {
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("Check "+checks);}
 public static void main(String[] args)throws Exception {
  Account a=new Account(70001);String first=WebSessionService.token(a);
  check(first.length()==24&&Base64.getDecoder().decode(first).length==16);
  check(first.equals(a.getSecurityToken()));
  for(int i=0;i<20;i++)check(first.equals(WebSessionService.token(a)));
  Account restart=new Account(70001);String second=WebSessionService.token(restart);check(!first.equals(second));
  Account parallel=new Account(70002);var latch=new CountDownLatch(1);
  try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
   List<Future<String>> tasks=new ArrayList<>();for(int i=0;i<64;i++)tasks.add(executor.submit(()->{latch.await();return WebSessionService.token(parallel);}));latch.countDown();
   String expected=tasks.getFirst().get();for(var task:tasks)check(task.get().equals(expected));
  }
  var packet=new SM_SECURITY_TOKEN(second.getBytes(StandardCharsets.US_ASCII));ByteBuffer bytes=ByteBuffer.allocate(100);packet.setBuf(bytes);
  var write=SM_SECURITY_TOKEN.class.getDeclaredMethod("writeImpl",AionConnection.class);write.setAccessible(true);write.invoke(packet,(Object)null);
  check(bytes.position()==49);bytes.flip();check(bytes.get()==0);byte[] nativeToken=new byte[24];bytes.get(nativeToken);check(Arrays.equals(nativeToken,second.getBytes(StandardCharsets.US_ASCII)));while(bytes.hasRemaining())check(bytes.get()==0);
  String clientHex=HexFormat.of().formatHex(Arrays.copyOf(nativeToken,16));check(clientHex.length()==32);check(!clientHex.equals(HexFormat.of().formatHex(first.substring(0,16).getBytes(StandardCharsets.US_ASCII))));
  // Exercise the transformed packet entry to resolve its inherited generic connection accessor.
  var enter=new com.aionemu.gameserver.network.aion.clientpackets.CM_ENTER_WORLD(0,Set.of());
  var run=enter.getClass().getDeclaredMethod("runImpl");run.setAccessible(true);
  try{run.invoke(enter);throw new AssertionError("Missing connection accepted");}
  catch(java.lang.reflect.InvocationTargetException error){check(error.getCause() instanceof NullPointerException);check(error.getCause().getStackTrace()[0].getClassName().equals(WebSessionService.class.getName()));}
  System.out.println("PASS: "+checks+" synthetic checks; fresh restart token, stable reentry, concurrent initialization and native NA packet format");
 }
}
