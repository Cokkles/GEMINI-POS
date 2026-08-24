const $=id=>document.getElementById(id);
const state={health:null,auth:null,capabilities:null,diagnostics:null};

async function api(path,options={}){
  const controller=new AbortController();
  const timeout=setTimeout(()=>controller.abort(),5000);
  try{
    const response=await fetch(path,{credentials:'same-origin',headers:{Accept:'application/json',...(options.headers||{})},...options,signal:controller.signal});
    const body=await response.json();
    if(!response.ok)throw new Error(body.message||body.error||`HTTP ${response.status}`);
    return body;
  }finally{clearTimeout(timeout)}
}

function text(id,value){const node=$(id);if(node)node.textContent=value??'-'}
function formatUptime(seconds=0){const h=Math.floor(seconds/3600),m=Math.floor((seconds%3600)/60),s=seconds%60;return h?`${h}h ${m}m`:m?`${m}m ${s}s`:`${s}s`}
function toast(message){const node=$('toast');node.textContent=message;node.classList.add('show');setTimeout(()=>node.classList.remove('show'),2600)}

function render(){
  const {health,auth,capabilities,diagnostics}=state;
  if(health){
    const ready=health.status==='AVAILABLE';
    text('serviceMetric',health.status);text('upstreamMetric',health.upstream.apps_script);text('uptimeMetric',formatUptime(health.uptime_seconds));text('stripMessage',ready?'Helper available on loopback':'Helper response received');text('gatewayState',health.upstream.apps_script.replaceAll('_',' '));text('healthDetail',JSON.stringify(health,null,2));
    text('serviceState',ready?'AVAILABLE':'DEGRADED');$('serviceState').className=`state ${ready?'':'failed'}`;$('liveDot').className=`live-dot ${ready?'ready':'failed'}`;
    document.querySelectorAll('[data-version]').forEach(n=>n.textContent=health.version);
  }
  if(auth){
    text('authMetric',auth.authenticated?'SIGNED IN':'SIGNED OUT');text('authIdentity',auth.identity?.email||'No identity');text('providerBadge',`${auth.provider.replaceAll('_',' ')} / ${auth.credential}`.toUpperCase());text('identityName',auth.identity?.displayName||'Not authenticated');text('identityEmail',auth.identity?.email||'No helper session');text('identityAvatar',(auth.identity?.displayName||'?').slice(0,1).toUpperCase());
    $('authButton').firstChild.textContent=auth.authenticated?'Authenticated ':'Authenticate ';$('authButtonSecondary').textContent=auth.authenticated?'Authenticated':'Authenticate';$('logoutButton').disabled=!auth.authenticated;
  }
  if(capabilities){
    text('capabilityCount',capabilities.capabilities.length);$('capabilities').innerHTML=capabilities.capabilities.map(value=>`<span class="capability">${escapeHtml(value)}</span>`).join('');
  }
  if(diagnostics){
    text('endpointValue',`Port ${diagnostics.port}`);const items=[['RUNTIME',`.NET ${diagnostics.runtime}`],['ARCHITECTURE',diagnostics.process_architecture],['AUTH PROVIDER',diagnostics.auth.replaceAll('_',' ')],['HEARTBEATS',String(diagnostics.heartbeat.count)]];$('diagnostics').innerHTML=items.map(([label,value])=>`<div class="diagnostic"><small>${label}</small><strong>${escapeHtml(value)}</strong></div>`).join('');
    const last=diagnostics.heartbeat.last_beat;text('workerLabel',last?'Heartbeat active':'Worker scheduled');text('workerDetail',last?`Last beat ${new Date(last).toLocaleTimeString()}`:'First beat occurs after configured interval');
  }
}

async function refresh(showToast=false){
  try{
    const [health,auth,capabilities,diagnostics]=await Promise.all([api('/api/v1/health'),api('/api/v1/auth/status'),api('/api/v1/capabilities'),api('/api/v1/diagnostics')]);
    Object.assign(state,{health,auth,capabilities,diagnostics});render();if(showToast)toast('Helper status refreshed');
  }catch(error){text('stripMessage',error.name==='AbortError'?'Status check timed out':error.message);text('serviceState','UNAVAILABLE');$('serviceState').className='state failed';$('liveDot').className='live-dot failed';if(showToast)toast('Unable to refresh helper');}
}

async function authenticate(){
  if(state.auth?.authenticated){showView('connection');return}
  try{
    const login=await api('/api/v1/auth/login',{method:'POST'});
    if(login.authorization_url.startsWith('/api/v1/auth/callback')){await api(login.authorization_url);await refresh();toast('Development session created')}
    else{window.location.assign(login.authorization_url)}
  }catch(error){toast(`Authentication failed: ${error.message}`)}
}

async function logout(){try{await api('/api/v1/auth/logout',{method:'POST'});await refresh();toast('Local session ended')}catch(error){toast(`Logout failed: ${error.message}`)}}
function escapeHtml(value){return String(value).replace(/[&<>'"]/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[char]))}
function showView(id){document.querySelectorAll('.view').forEach(v=>v.classList.toggle('active',v.id===id));document.querySelectorAll('.nav-item[data-view]').forEach(v=>v.classList.toggle('active',v.dataset.view===id));closeNav()}
function closeNav(){$('nav').classList.remove('open');$('scrim').classList.remove('show')}

document.querySelectorAll('[data-view]').forEach(button=>button.addEventListener('click',()=>showView(button.dataset.view)));
document.querySelectorAll('[data-view-link]').forEach(button=>button.addEventListener('click',()=>showView(button.dataset.viewLink)));
$('menuButton').addEventListener('click',()=>{$('nav').classList.add('open');$('scrim').classList.add('show')});$('scrim').addEventListener('click',closeNav);
$('refreshButton').addEventListener('click',()=>refresh(true));$('authButton').addEventListener('click',authenticate);$('authButtonSecondary').addEventListener('click',authenticate);$('logoutButton').addEventListener('click',logout);
refresh();setInterval(()=>refresh(),30000);

