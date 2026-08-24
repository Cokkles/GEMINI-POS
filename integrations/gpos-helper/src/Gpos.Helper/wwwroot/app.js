const $=id=>document.getElementById(id);
const state={health:null,auth:null,capabilities:null,diagnostics:null,setup:null};

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
  const {health,auth,capabilities,diagnostics,setup}=state;
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
  if(setup){
    text('setupMode',setup.production_ready?'PRODUCTION READY':setup.mode);$('setupList').innerHTML=setup.checks.map(check=>`<div class="setup-item ${check.ready?'ready':''}"><span class="setup-state">${check.ready?'OK':'!'}</span><div><strong>${escapeHtml(check.id.replaceAll('_',' '))}</strong><small>${escapeHtml(check.detail)}</small></div></div>`).join('');
  }
}

async function refresh(showToast=false){
  try{
    const [health,auth,capabilities,diagnostics,setup]=await Promise.all([api('/api/v1/health'),api('/api/v1/auth/status'),api('/api/v1/capabilities'),api('/api/v1/diagnostics'),api('/api/v1/setup/status')]);
    Object.assign(state,{health,auth,capabilities,diagnostics,setup});render();if(showToast)toast('Helper status refreshed');
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
async function queryCalendar(event){
  event.preventDefault();const question=$('calendarQuestion').value.trim();if(!question)return;
  if(!state.auth?.authenticated){showView('connection');toast('Authenticate before querying AEGIS');return}
  const submit=$('calendarSubmit');submit.disabled=true;text('calendarState','ASKING');$('calendarResponse').className='calendar-response loading';$('calendarResponse').innerHTML='<strong>Checking your calendar.</strong><span>The request will stop automatically if the upstream service does not respond.</span>';
  try{
    const result=await api('/api/v1/aegis/calendar/query',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({question,history:[]})});
    text('calendarState','COMPLETE');$('calendarResponse').className='calendar-response';$('calendarResponse').innerHTML=`<pre>${escapeHtml(JSON.stringify(result,null,2))}</pre>`;
  }catch(error){
    const message=error.name==='AbortError'?'Calendar request timed out. Please try again.':error.message;text('calendarState','UNAVAILABLE');$('calendarResponse').className='calendar-response failed';$('calendarResponse').innerHTML=`<strong>Calendar unavailable</strong><span>${escapeHtml(message)}</span>`;
  }finally{submit.disabled=false}
}
async function loadSnapshot(){
  if(!state.auth?.authenticated){showView('connection');toast('Authenticate before loading AEGIS');return}
  const button=$('snapshotRefresh');button.disabled=true;text('snapshotState','LOADING');$('snapshotResult').className='snapshot-result loading';$('snapshotResult').innerHTML='<strong>Loading AEGIS snapshot.</strong><span>This request has a finite timeout and will not retry forever.</span>';
  try{
    const result=await api('/api/v1/aegis/dashboard');const entries=result&&typeof result==='object'&&!Array.isArray(result)?Object.entries(result):[];
    text('snapshotState','CURRENT');$('snapshotMetrics').innerHTML=(entries.length?entries.slice(0,4):[['state','available']]).map(([key,value])=>`<article class="metric"><small>${escapeHtml(String(key).replaceAll('_',' '))}</small><strong>${escapeHtml(summaryValue(value))}</strong><span>AEGIS response</span></article>`).join('');
    $('snapshotResult').className='snapshot-result';$('snapshotResult').innerHTML=`<pre>${escapeHtml(JSON.stringify(result,null,2))}</pre>`;
  }catch(error){
    const message=error.name==='AbortError'?'Snapshot request timed out. Please try again.':error.message;text('snapshotState','UNAVAILABLE');$('snapshotMetrics').innerHTML='<article class="metric"><small>STATE</small><strong>UNAVAILABLE</strong><span>Terminal result</span></article>';$('snapshotResult').className='snapshot-result failed';$('snapshotResult').innerHTML=`<strong>AEGIS snapshot unavailable</strong><span>${escapeHtml(message)}</span>`;
  }finally{button.disabled=false}
}
async function loadActivity(showToast=false){
  try{
    const includeRoutine=$('activityRoutine').checked;const result=await api(`/api/v1/activity?limit=30&include_routine=${includeRoutine}`);const entries=result.entries||[];text('activityCount',`${entries.length} REQUEST${entries.length===1?'':'S'}`);
    $('activityTable').innerHTML=entries.length?`<div class="activity-row activity-labels"><span>TIME</span><span>METHOD</span><span>ENDPOINT</span><span>STATUS</span><span>DURATION</span></div>${entries.map(entry=>`<div class="activity-row"><span>${escapeHtml(new Date(entry.timestamp).toLocaleTimeString())}</span><strong>${escapeHtml(entry.method)}</strong><code>${escapeHtml(entry.endpoint)}</code><span class="activity-status ${entry.status>=400?'bad':'good'}">${entry.status}</span><span>${entry.durationMs} ms</span></div>`).join('')}`:'<div class="activity-empty">No helper requests have been recorded yet.</div>';
    if(showToast)toast('Activity refreshed');
  }catch(error){$('activityTable').innerHTML=`<div class="activity-empty">Activity unavailable: ${escapeHtml(error.message)}</div>`}
}
function summaryValue(value){if(value===null||value===undefined)return '-';if(Array.isArray(value))return `${value.length} items`;if(typeof value==='object')return `${Object.keys(value).length} fields`;return String(value).slice(0,42)}
function escapeHtml(value){return String(value).replace(/[&<>'"]/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[char]))}
function showView(id){document.querySelectorAll('.view').forEach(v=>v.classList.toggle('active',v.id===id));document.querySelectorAll('.nav-item[data-view]').forEach(v=>v.classList.toggle('active',v.dataset.view===id));if(id==='activity')loadActivity();closeNav()}
function closeNav(){$('nav').classList.remove('open');$('scrim').classList.remove('show')}

document.querySelectorAll('[data-view]').forEach(button=>button.addEventListener('click',()=>showView(button.dataset.view)));
document.querySelectorAll('[data-view-link]').forEach(button=>button.addEventListener('click',()=>showView(button.dataset.viewLink)));
$('menuButton').addEventListener('click',()=>{$('nav').classList.add('open');$('scrim').classList.add('show')});$('scrim').addEventListener('click',closeNav);
$('refreshButton').addEventListener('click',()=>refresh(true));$('authButton').addEventListener('click',authenticate);$('authButtonSecondary').addEventListener('click',authenticate);$('logoutButton').addEventListener('click',logout);
$('calendarForm').addEventListener('submit',queryCalendar);document.querySelectorAll('[data-calendar-prompt]').forEach(button=>button.addEventListener('click',()=>{$('calendarQuestion').value=button.dataset.calendarPrompt;$('calendarQuestion').focus()}));
$('snapshotRefresh').addEventListener('click',loadSnapshot);
$('activityRefresh').addEventListener('click',()=>loadActivity(true));
$('activityRoutine').addEventListener('change',()=>loadActivity());
refresh();setInterval(()=>refresh(),30000);

