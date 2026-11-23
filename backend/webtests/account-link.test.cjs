const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {webcrypto} = require('node:crypto');
const script = fs.readFileSync('backend/app/web/account-link.js','utf8');

function browser(search='', saved=null) {
  const elements = Object.fromEntries(['status','login','claim','code','confirmed'].map(id=>['#'+id,{textContent:'',value:'',checked:false,hidden:false}]));
  const storage = new Map(saved ? [['calenflex.pkce',JSON.stringify(saved)]] : []);
  const requests=[]; let assigned=''; let cleared=false;
  const context={crypto:webcrypto, TextEncoder, URL, URLSearchParams, Uint8Array, btoa, Date, Error, JSON,
    document:{querySelector:key=>elements[key]}, sessionStorage:{getItem:key=>storage.get(key),setItem:(k,v)=>storage.set(k,v),removeItem:key=>storage.delete(key)},
    location:{search,assign:url=>{assigned=String(url);}},history:{replaceState:()=>{cleared=true;}},
    fetch:async (url,options={})=>{
      requests.push([String(url),options]);
      if(url==='/api/v1/connections/link/config') return {ok:true,json:async()=>({issuer:'https://issuer.test/',clientId:'public',redirectUri:'https://app.test/connect',scopes:'openid memory:read memory:write schedule:read schedule:write',audienceParameter:'resource',resource:'https://app.test/mcp'})};
      if(String(url).includes('openid-configuration')) return {ok:true,json:async()=>({issuer:'https://issuer.test/',authorization_endpoint:'https://issuer.test/authorize',token_endpoint:'https://issuer.test/token'})};
      if(url==='https://issuer.test/token') return {ok:true,json:async()=>({access_token:'signed-fixture-token',token_type:'Bearer'})};
      if(url==='/api/v1/connections/identity') return {ok:true,json:async()=>({authentication:'oidc',workspaceId:'verified'})};
      if(url==='/api/v1/connections/link/claim') return {ok:true,json:async()=>({linked:true})};
      throw Error('Unexpected request');
    }};
  return {elements,storage,requests,assigned:()=>assigned,cleared:()=>cleared,run:()=>vm.runInNewContext(script,context)};
}

test('PKCE S256, state, resource and explicit confirmed claim use the verified token',async()=>{
  const first=browser();await first.run();await first.elements['#login'].onclick();
  const auth=new URL(first.assigned());const saved=JSON.parse(first.storage.get('calenflex.pkce'));
  assert.equal(auth.searchParams.get('state'),saved.state);
  assert.equal(auth.searchParams.get('code_challenge_method'),'S256');
  const challenge=Buffer.from(await webcrypto.subtle.digest('SHA-256',new TextEncoder().encode(saved.verifier))).toString('base64url');
  assert.equal(auth.searchParams.get('code_challenge'),challenge);
  assert.equal(auth.searchParams.get('resource'),'https://app.test/mcp');
  const callback=browser('?code=authorization-code&state='+saved.state,saved);await callback.run();
  assert.equal(callback.cleared(),true);assert.equal(callback.storage.size,0);
  const tokenRequest=callback.requests.find(([url])=>url.endsWith('/token'));
  assert.equal(tokenRequest[1].body.get('code_verifier'),saved.verifier);
  callback.elements['#code'].value='one-time-code-of-at-least-32-characters';
  await callback.elements['#claim'].onsubmit({preventDefault(){}});
  assert.equal(callback.requests.filter(([url])=>url.endsWith('/claim')).length,0);
  callback.elements['#confirmed'].checked=true;
  await callback.elements['#claim'].onsubmit({preventDefault(){}});
  const claim=callback.requests.find(([url])=>url.endsWith('/claim'));
  assert.equal(claim[1].headers.Authorization,'Bearer signed-fixture-token');
  assert.equal(JSON.parse(claim[1].body).confirmed,true);
  assert.match(callback.elements['#status'].textContent,/Conexiones vinculadas/);
});

test('wrong or expired state never reaches token exchange',async()=>{
  for(const saved of [{state:'expected',verifier:'verifier',at:Date.now()},{state:'wrong',verifier:'verifier',at:0}]) {
    const callback=browser('?code=evil&state=wrong',saved);await callback.run();
    assert.equal(callback.requests.some(([url])=>url.endsWith('/token')),false);
    assert.match(callback.elements['#status'].textContent,/inválido/);
  }
});
