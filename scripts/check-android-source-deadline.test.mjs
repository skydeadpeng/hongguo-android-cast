import test from 'node:test';
import assert from 'node:assert/strict';
import axiosNative,{setNativeCall} from '../src/android/mac-source/axios-native.mjs';
const url='https://vod.snssdk.com/fixture?signature=A%2BB%3D';
test('native source request receives the same bounded budget and a queue-expiry deadline',async()=>{
  for(const [requested,wanted]of [[1234,1234],[50000,12000],[0,12000],[-2,1],[2.5,2]]){
    let call;setNativeCall(async(...args)=>{call=args;return{status:200,body:'{}'};});
    const before=Date.now();await axiosNative({url,timeout:requested});const after=Date.now();
    assert.equal(call[0],'appRequest');assert.equal(call[2],wanted);assert.equal(call[1].timeoutMs,wanted);
    assert.ok(Number.isSafeInteger(call[1].deadlineMs));assert.ok(call[1].deadlineMs>=before+wanted&&call[1].deadlineMs<=after+wanted);
  }
});
test('local deadline metadata never changes the signed URL, headers or JSON payload',async()=>{
  let call;setNativeCall(async(...args)=>{call=args;return{status:200,body:'{}'};});
  const headers={'x-test':'A+/='},body='{"中文":1}';await axiosNative({url,method:'POST',headers,data:body,timeout:42});
  assert.equal(call[1].url,url);assert.deepEqual(call[1].headers,headers);assert.equal(call[1].body,body);assert.equal(call[1].method,'POST');
  assert.equal(call[1].timeoutMs,42);assert.equal(Object.keys(headers).length,1);
});
test('pre-cancelled source opens no native request and in-flight cancel stays typed',async()=>{
  let calls=0;const cancelled=new AbortController();cancelled.abort();setNativeCall(async()=>{calls++;return{status:200,body:'{}'};});
  await assert.rejects(axiosNative({url,signal:cancelled.signal}),e=>e.code==='ERR_CANCELED');assert.equal(calls,0);
  const live=new AbortController();setNativeCall(()=>new Promise(()=>{calls++;}));const pending=axiosNative({url,signal:live.signal});live.abort();
  await assert.rejects(pending,e=>e.code==='ERR_CANCELED');assert.equal(calls,1);
});
