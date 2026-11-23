'use strict';
(async () => {
  const status = document.querySelector('#status');
  const login = document.querySelector('#login');
  const claim = document.querySelector('#claim');
  const show = message => { status.textContent = message; };
  const encode = bytes => btoa(String.fromCharCode(...bytes)).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
  const random = () => encode(crypto.getRandomValues(new Uint8Array(32)));
  let accessToken = '';
  try {
    const configResponse = await fetch('/api/v1/connections/link/config', {cache:'no-store'});
    if (!configResponse.ok) throw Error('El servidor necesita configurar el cliente público OAuth y HTTPS. Consultá docs/MCP.md.');
    const config = await configResponse.json();
    const discoveryResponse = await fetch(config.issuer.replace(/\/$/,'')+'/.well-known/openid-configuration', {cache:'no-store'});
    if (!discoveryResponse.ok) throw Error('El proveedor no permite descubrir su configuración desde este navegador.');
    const provider = await discoveryResponse.json();
    if (provider.issuer !== config.issuer || !provider.authorization_endpoint.startsWith('https://') || !provider.token_endpoint.startsWith('https://')) throw Error('La configuración del proveedor OAuth es incompatible.');
    login.onclick = async () => {
      const verifier = random(), state = random();
      sessionStorage.setItem('calenflex.pkce', JSON.stringify({verifier,state,at:Date.now()}));
      const challenge = encode(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(verifier))));
      const url = new URL(provider.authorization_endpoint);
      const values = {client_id:config.clientId,redirect_uri:config.redirectUri,response_type:'code',scope:config.scopes,state,code_challenge:challenge,code_challenge_method:'S256'};
      values[config.audienceParameter] = config.resource;
      Object.entries(values).forEach(([key,value])=>url.searchParams.set(key,value));
      location.assign(url);
    };
    const query = new URLSearchParams(location.search);
    history.replaceState(null,'','/connect');
    if (query.has('error')) throw Error('El proveedor no autorizó la conexión. Podés volver a ingresar.');
    if (query.has('code')) {
      const saved = JSON.parse(sessionStorage.getItem('calenflex.pkce') || 'null');
      sessionStorage.removeItem('calenflex.pkce');
      if (!saved || saved.state !== query.get('state') || Date.now()-saved.at > 600000) throw Error('La sesión OAuth venció o su estado es inválido. Volvé a ingresar.');
      const response = await fetch(provider.token_endpoint,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'authorization_code',client_id:config.clientId,redirect_uri:config.redirectUri,code:query.get('code'),code_verifier:saved.verifier})});
      if (!response.ok) throw Error('No se pudo intercambiar el código. Revisá PKCE, CORS y redirect URI en el proveedor.');
      const tokens = await response.json();
      if (tokens.token_type?.toLowerCase() !== 'bearer' || !tokens.access_token) throw Error('El proveedor no devolvió un access token válido.');
      accessToken = tokens.access_token;
      const who = await fetch('/api/v1/connections/identity',{headers:{Authorization:'Bearer '+accessToken},cache:'no-store'});
      if (!who.ok) throw Error('El servidor rechazó el access token. Revisá audiencia, firma, emisor y scopes.');
      const identity = await who.json();
      if (identity.authentication !== 'oidc') throw Error('Se requiere una identidad OAuth.');
      show('Sesión OAuth verificada. Ingresá el código del teléfono.');
      login.hidden = true; claim.hidden = false;
    } else show('Ingresá con tu proveedor OAuth para comenzar.');
    claim.onsubmit = async event => {
      event.preventDefault();
      if (!document.querySelector('#confirmed').checked || !accessToken) return;
      const response = await fetch('/api/v1/connections/link/claim',{method:'POST',headers:{Authorization:'Bearer '+accessToken,'Content-Type':'application/json'},body:JSON.stringify({code:document.querySelector('#code').value.trim(),confirmed:true})});
      if (!response.ok) { show('No se vinculó: el código puede estar vencido, usado o el espacio ya contiene datos. No se reemplazó información.'); return; }
      accessToken = ''; claim.hidden = true;
      show('Conexiones vinculadas. Android y las herramientas MCP autorizadas usarán el mismo espacio. Ahora conectá MCP en ChatGPT y completá su consentimiento.');
    };
  } catch (error) { show(error.message); }
})();
