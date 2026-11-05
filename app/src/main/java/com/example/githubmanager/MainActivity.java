package com.example.githubmanager;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.CookieManager;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class MainActivity extends Activity {
  private static final int PICK_ZIP=41, PICK_FILE=42, PICK_FILES=43, PICK_FOLDER=44, PICK_NEW_REPO_ZIP=45, PICK_SAVE_ARTIFACT=46, PICK_SAVE_REPO_ZIP=47, PICK_SAVE_SELECTED_ZIP=48, PICK_SAVE_ONE=49, PICK_SAVE_REPO_TREE=50, PICK_SAVE_SELECTED_TREE=51;
  private static final long MAX_BATCH_BYTES=18_000_000L, MAX_FILE_BYTES=5_000_000L;
  private static final int MAX_BATCH_FILES=250;
  private static final int MAX_EXPORT_FILES=400, MAX_REPO_EXTRACT_FILES=12000;
  private static final long MAX_EXPORT_BYTES=100_000_000L, MAX_SINGLE_EXPORT_FILE_BYTES=18_000_000L, MAX_REPO_EXTRACT_BYTES=1_000_000_000L;
  private DownloadJob pendingDownload;
  private static class DownloadJob {
    int kind; long artifactId; boolean extractApk;
    String owner,repo,branch,name,sha,baseFolder;
    ArrayList<String> paths;
  }
  private static class ArtifactFound {
    JSONObject latest; JSONObject success; ArrayList<JSONObject> artifacts=new ArrayList<>();
  }
  private static class RemoteStream implements AutoCloseable {
    HttpURLConnection connection; InputStream input; long length;
    RemoteStream(HttpURLConnection c,InputStream i,long l){connection=c;input=i;length=l;}
    @Override public void close()throws IOException{try{input.close();}finally{connection.disconnect();}}
  }
  private final ExecutorService executor=Executors.newSingleThreadExecutor();
  private LinearLayout root, body; private TextView status;
  private AlertDialog operationDialog;
  private TextView operationStep;
  private ProgressBar operationBar;
  private WebView activeWebView;
  private final ArrayList<JSONObject> repoCache=new ArrayList<>();
  private LinearLayout repositoryResults;
  private EditText repositorySearch;
  private Spinner repositorySort;
  private TextView repositoryCount;
  private String accountLogin="";
  private String token="", user="", repo="", branch="main", folder="";
  private final LinkedHashMap<String,Entry> visible=new LinkedHashMap<>();
  private final LinkedHashSet<String> chosen=new LinkedHashSet<>();
  private final ArrayList<CheckBox> selectionBoxes=new ArrayList<>();
  private static class Entry {String path,sha; boolean dir; Entry(String p,String s,boolean d){path=p;sha=s;dir=d;}}
  interface Work<T>{ T go() throws Exception; }
  interface Done<T>{ void ok(T value) throws Exception; }
  @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.rgb(11,18,34));getWindow().setNavigationBarColor(Color.rgb(11,18,34)); token=getPreferences(0).getString("token","");accountLogin=getPreferences(0).getString("login","");home();}
  private void frame(String title){
    if(activeWebView!=null){activeWebView.stopLoading();activeWebView.destroy();activeWebView=null;}
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
    root=new LinearLayout(this);root.setOrientation(1);root.setPadding(18,18,18,26);root.setBackgroundColor(Color.rgb(12,19,36));
    scroll.addView(root);
    scroll.setOnApplyWindowInsetsListener((v,insets)->{
      v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
      return insets;
    });
    setContentView(scroll);
    label(title,25,true);status=label("",13,false);
    body=new LinearLayout(this);body.setOrientation(1);root.addView(body);
  }
  private TextView label(String text,int size,boolean bold){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(Color.WHITE);if(bold)t.setTypeface(null,Typeface.BOLD);t.setPadding(5,10,5,10);root.addView(t);return t;}
  private TextView note(String text){TextView t=new TextView(this);t.setText(text);t.setTextColor(0xffbbcee7);t.setTextSize(14);t.setPadding(8,10,8,10);body.addView(t);return t;}
  private void button(String text,Runnable job){buttonInto(body,text,job);}
  private void buttonInto(LinearLayout where,String text,Runnable job){
    Button b=new Button(this);b.setAllCaps(false);b.setText(text);b.setTextColor(Color.WHITE);
    b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff244777));
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,5,0,5);
    where.addView(b,p);b.setOnClickListener(v->job.run());
  }
  private void message(String msg){new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK",null).show();}
  private void fail(Exception e){
    String err=String.valueOf(e.getMessage());
    if((err.contains("GitHub HTTP 403")||err.contains("GitHub HTTP 404")) && !repo.isEmpty() && !err.toLowerCase(Locale.ROOT).contains("artifact") && !err.contains("/actions"))
      err += "\n\n🔑 Verifique as permissões do token para "+user+"/"+repo+". " +
        "GitHub pode responder 404 mesmo quando o repositório existe, se o token não tem acesso. " +
        "No token fine-grained, confira o proprietário, se o repositório está incluído em Only select repositories, " +
        "e Contents = Read and write. Acesso de leitura a repositório público NÃO dá permissão de gravar. " +
        "Para editar .github/workflows é necessário também Workflows = Read and write.";
    message("Não foi possível concluir:\n"+err);
  }
  private <T> void run(String desc,Work<T> work,Done<T> done){
    TextView currentStatus=status;
    currentStatus.setText("⏳ "+desc);
    executor.submit(()->{try{T result=work.go();runOnUiThread(()->{
      currentStatus.setText("✓ "+desc);try{done.ok(result);}catch(Exception e){fail(e);}
    });}catch(Exception e){runOnUiThread(()->{currentStatus.setText("✗ "+desc);fail(e);});}});
  }
  private void busyStart(String title,int steps){
    LinearLayout content=new LinearLayout(this);content.setOrientation(1);content.setPadding(35,25,35,15);
    operationStep=new TextView(this);operationStep.setText("Iniciando... Nenhum dado foi enviado ainda.");
    operationStep.setTextSize(15);content.addView(operationStep);
    operationBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
    operationBar.setMax(Math.max(1,steps));operationBar.setIndeterminate(steps<=0);
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,35);lp.setMargins(0,20,0,0);
    content.addView(operationBar,lp);
    operationDialog=new AlertDialog.Builder(this).setTitle("⏳ "+title)
      .setView(content).setCancelable(false).create();
    operationDialog.show();
  }
  private void busyTick(String text,int finished,int total){
    runOnUiThread(()->{
      if(operationStep!=null)operationStep.setText(text+(total>0?" — "+finished+"/"+total:""));
      if(operationBar!=null){
        if(total>0){operationBar.setIndeterminate(false);operationBar.setMax(total);operationBar.setProgress(finished);}
        else operationBar.setIndeterminate(true);
      }
    });
  }
  private void busyEnd(){
    if(operationDialog!=null){operationDialog.dismiss();operationDialog=null;}
    operationBar=null;operationStep=null;
  }
  private <T> void runBusy(String desc,int steps,Work<T> work,Done<T> done){
    TextView currentStatus=status;currentStatus.setText("⏳ "+desc);
    busyStart(desc,steps);
    executor.submit(()->{try{T result=work.go();runOnUiThread(()->{
      busyEnd();currentStatus.setText("✓ "+desc);
      try{done.ok(result);}catch(Exception e){fail(e);}
    });}catch(Exception e){runOnUiThread(()->{busyEnd();currentStatus.setText("✗ "+desc);fail(e);});}});
  }
  private void input(String heading,String value,java.util.function.Consumer<String> done){EditText e=new EditText(this);e.setSingleLine(true);e.setText(value);new AlertDialog.Builder(this).setTitle(heading).setView(e).setNegativeButton("Cancelar",null).setPositiveButton("Continuar",(d,w)->done.accept(e.getText().toString().trim())).show();}
  private void confirm(String msg,Runnable done){new AlertDialog.Builder(this).setTitle("Confirmar alteração no GitHub").setMessage(msg).setNegativeButton("Cancelar",null).setPositiveButton("Confirmar",(d,w)->done.run()).show();}
  private String safe(String p){if(p==null)throw new IllegalArgumentException("Caminho vazio");p=p.replace('\\','/');if(p.startsWith("/")||p.contains("\u0000"))throw new IllegalArgumentException("Caminho absoluto inválido");String[] a=p.split("/",-1);for(String s:a)if(s.equals("..")||s.equals("."))throw new IllegalArgumentException("Caminho relativo inválido");while(p.startsWith("./"))p=p.substring(2);return p;}
  private String enc(String x){try{return java.net.URLEncoder.encode(x,"UTF-8").replace("+","%20");}catch(Exception e){return x;}}
  private String segments(String x){StringBuilder b=new StringBuilder();for(String s:x.split("/")){if(b.length()>0)b.append('/');b.append(enc(s));}return b.toString();}
  private JSONObject request(String method,String path,JSONObject data)throws Exception{
    URL url=new URL("https://api.github.com"+path);HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setRequestMethod(method);c.setConnectTimeout(18000);c.setReadTimeout(45000);c.setRequestProperty("Accept","application/vnd.github+json");c.setRequestProperty("X-GitHub-Api-Version","2022-11-28");c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("User-Agent","GitHubManagerAndroid/1.4");
    if(data!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");byte[] b=data.toString().getBytes(StandardCharsets.UTF_8);try(OutputStream o=c.getOutputStream()){o.write(b);}}
    int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();String txt="";if(in!=null){try(InputStream s=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=s.read(buf))!=-1)out.write(buf,0,n);txt=out.toString("UTF-8");}}
    c.disconnect();if(code<200||code>=300)throw new IOException("GitHub HTTP "+code+" — "+(txt.length()>500?txt.substring(0,500):txt));
    if(txt.isEmpty())return new JSONObject();if(txt.trim().startsWith("["))return new JSONObject().put("items",new JSONArray(txt));return new JSONObject(txt);
  }
  private String base(){return "/repos/"+enc(user)+"/"+enc(repo);}
  private void home(){
    frame("🐙 GitHub Manager 1.4");
    note("ZIP → repositório → Actions → APK. Novo: baixar APK direto, arquivos, pastas e repositórios; as funções anteriores foram preservadas.");
    if(token.isEmpty()){
      button("🔑 Conectar com token pessoal",()->input("Cole o token (não compartilhe)","",s->{
        if(s.isEmpty())return;token=s;
        run("Validar credencial",()->request("GET","/user",null).getString("login"),v->{
          getPreferences(0).edit().putString("token",token).putString("login",v).apply();
          accountLogin=v;user=v;repos();
        });
      }));
    }else{
      button("🚀 ZIP → criar repositório e publicar",()->pick(PICK_NEW_REPO_ZIP,"application/zip"));
      button("📚 Meus repositórios",this::repos);
      button("➕ Criar repositório",this::createRepo);
      button("🔄 Validar token",()->run("Testar conexão",()->request("GET","/user",null).getString("login"),v->{
        accountLogin=v;getPreferences(0).edit().putString("login",v).apply();
        message("Conectado como "+v);
      }));
      button("🔁 Trocar token",()->input("Novo token (não compartilhe)","",s->{
        if(s.isEmpty())return;
        String previous=token;token=s;
        run("Validar novo token",()->{try{return request("GET","/user",null).getString("login");}
          catch(Exception x){token=previous;throw x;}},v->{
          accountLogin=v;user=v;getPreferences(0).edit().putString("token",token).putString("login",v).apply();home();
        });
      }));
      button("🔒 Desconectar / apagar token",()->confirm("Apagar o token armazenado neste aplicativo?",()->{
        getPreferences(0).edit().clear().apply();token="";accountLogin="";user="";home();
      }));
    }
    note("Segurança: o token é salvo em preferências privadas do Android (sem Android Keystore nesta versão). Use token restrito e prazo curto; nunca compartilhe.");
  }
  private void repos(){
    frame("📚 Repositórios");button("← Início",this::home);button("🚀 ZIP → novo repositório + publicar",()->pick(PICK_NEW_REPO_ZIP,"application/zip"));button("➕ Criar novo repositório",this::createRepo);
    button("🔎 Abrir digitando o nome do repositório",()->input("Nome (ou proprietário/nome)","",x->{
      if(x.isEmpty())return;
      String[] bits=x.split("/",-1);
      if(bits.length>2 || bits[bits.length-1].isEmpty()){message("Use nome ou proprietário/nome");return;}
      String own=bits.length==2?bits[0]:accountLogin;
      if(own.isEmpty() || !own.matches("[A-Za-z0-9-]+") || !bits[bits.length-1].matches("[A-Za-z0-9._-]+")){
        message("Nome de repositório inválido. Para apenas o nome, valide o token primeiro.");return;
      }
      String name=bits[bits.length-1];
      run("Abrir repositório",()->request("GET","/repos/"+enc(own)+"/"+enc(name),null),r->{
        user=own;repo=r.optString("name",name);branch=r.optString("default_branch","main");folder="";chosen.clear();browse();
      });
    }));
    repositorySearch=new EditText(this);repositorySearch.setSingleLine(true);
    repositorySearch.setHint("🔍 Buscar pelo nome ou descrição");repositorySearch.setTextColor(Color.WHITE);
    body.addView(repositorySearch);
    String[] options={"Atualização mais recente", "Nome A → Z", "Nome Z → A", "Criado recentemente", "Criado há mais tempo", "Atualização mais antiga", "Último push"};
    repositorySort=new Spinner(this);
    ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options);
    repositorySort.setAdapter(adapter);body.addView(repositorySort);
    repositoryCount=note("Buscando repositórios...");
    repositoryResults=new LinearLayout(this);repositoryResults.setOrientation(1);body.addView(repositoryResults);
    repositorySearch.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence a,int b,int c,int d){}
      public void onTextChanged(CharSequence a,int b,int before,int count){renderRepos();}
      public void afterTextChanged(Editable e){}
    });
    repositorySort.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
      public void onItemSelected(android.widget.AdapterView<?> parent,View v,int position,long id){renderRepos();}
      public void onNothingSelected(android.widget.AdapterView<?> parent){}
    });
    run("Listar repositórios",()->{
      ArrayList<JSONObject> all=new ArrayList<>();
      for(int page=1;page<=10;page++){
        JSONArray part=request("GET","/user/repos?per_page=100&page="+page+"&sort=updated",null).getJSONArray("items");
        for(int i=0;i<part.length();i++)all.add(part.getJSONObject(i));
        if(part.length()<100)break;
      }
      return all;
    },a->{repoCache.clear();repoCache.addAll(a);renderRepos();});
  }
  private String dateOf(JSONObject object,String field){
    String value=object.optString(field,"");return value==null||value.equals("null")||value.length()<10?"—":value.substring(0,10);
  }
  private void renderRepos(){
    if(repositoryResults==null || repositorySearch==null || repositorySort==null || repositoryCount==null)return;
    repositoryResults.removeAllViews();
    String filter=repositorySearch.getText().toString().toLowerCase(Locale.ROOT).trim();
    int mode=repositorySort.getSelectedItemPosition();
    ArrayList<JSONObject> filtered=new ArrayList<>();
    for(JSONObject r:repoCache){
      String name=r.optString("name","");
      String desc=r.isNull("description")?"":r.optString("description","");
      if((name+" "+desc).toLowerCase(Locale.ROOT).contains(filter))filtered.add(r);
    }
    String field=mode==3||mode==4?"created_at":mode==6?"pushed_at":"updated_at";
    filtered.sort((a,b)->{
      String aa=a.optString(mode==1||mode==2?"name":field,"");
      String bb=b.optString(mode==1||mode==2?"name":field,"");
      int cmp=mode==1||mode==2?aa.compareToIgnoreCase(bb):aa.compareTo(bb);
      return mode==0||mode==2||mode==3||mode==6?-cmp:cmp;
    });
    repositoryCount.setText(filtered.size()+" repositório(s) exibido(s) de "+repoCache.size());
    for(JSONObject r:filtered){
      String own=r.optJSONObject("owner")!=null?r.optJSONObject("owner").optString("login",""):"";
      String name=r.optString("name","");
      String desc=r.isNull("description")?"":r.optString("description","");
      String showOwner=own.equalsIgnoreCase(accountLogin)?"":" • "+own;
      String caption=(r.optBoolean("private")?"🔒 ":"📁 ")+name+showOwner;
      caption+="\nCriado: "+dateOf(r,"created_at")+" • Atualizado: "+dateOf(r,"updated_at");
      if(!desc.isEmpty() && !desc.equals("null"))caption+="\n"+desc;
      buttonInto(repositoryResults,caption,()->{
        user=own;repo=name;branch=r.optString("default_branch","main");folder="";chosen.clear();browse();
      });
    }
    if(filtered.isEmpty()&&!repoCache.isEmpty())buttonInto(repositoryResults,"Nenhum resultado — limpar pesquisa",()->repositorySearch.setText(""));
  }
  private void createRepo(){input("Nome do novo repositório","",name->{if(!name.matches("[a-zA-Z0-9._-]{1,100}")){message("Nome inválido.");return;}new AlertDialog.Builder(this).setTitle("Visibilidade").setItems(new String[]{"Privado", "Público"},(d,which)->{try{JSONObject payload=new JSONObject().put("name",name).put("private",which==0).put("auto_init",true);run("Criar repositório",()->request("POST","/user/repos",payload),r->{user=r.getJSONObject("owner").getString("login");repo=r.getString("name");branch=r.optString("default_branch","main");folder="";browse();});}catch(Exception e){fail(e);}}).show();});}
  private void browse(){
    frame("📂 "+repo+" / "+(folder.isEmpty()?"raiz":folder));
    button("← Repositórios",this::repos);
    button("⬆ Pasta anterior",()->{if(folder.isEmpty()){repos();return;}int pos=folder.lastIndexOf('/');folder=pos<0?"":folder.substring(0,pos);chosen.clear();browse();});
    button("⚙️ Ações do repositório",this::repoOptions);
    button("🔐 Diagnosticar permissão de escrita",this::diagnosePermissions);
    button("📁 Criar pasta no GitHub",this::createGitFolder);
    button("📦 Enviar ZIP sem extrair no celular",()->pick(PICK_ZIP,"application/zip"));
    button("📄 Enviar um arquivo",()->pick(PICK_FILE,"*/*"));
    button("🗂 Enviar vários arquivos de uma vez",()->pick(PICK_FILES,"*/*"));
    button("📂 Enviar pasta inteira com subpastas",this::pickLocalFolder);
    button("📝 Criar arquivo de texto",()->input("Nome do arquivo (na pasta atual)","novo.txt",v->{if(v.isEmpty())return;edit((folder.isEmpty()?"":folder+"/")+v,"");}));
    button("⬇️ Baixar arquivos/pastas selecionados (ZIP)",this::downloadSelectedZip);
    button("📂 Baixar selecionados sem ZIP",this::downloadSelectedFolder);
    button("⬇️ Baixar pasta atual (ZIP)",this::downloadCurrentFolderZip);
    button("📦 Baixar repositório completo (ZIP)",this::downloadRepoZip);
    button("📁 Baixar repositório completo (pasta)",this::downloadRepoFolder);
    button("📲 Baixar último APK do Actions",this::latestApk);
    button("🗑 Excluir selecionados",this::removeSelected);
    button("📦 Copiar/mover selecionados",this::transferSelected);
    button("☐ Limpar seleção",()->{chosen.clear();for(CheckBox c:selectionBoxes)c.setChecked(false);status.setText("Seleção limpa");});
    button("☑ Selecionar todos os itens visíveis",()->{
      chosen.addAll(visible.keySet());
      for(CheckBox c:selectionBoxes)c.setChecked(true);
      status.setText("☑ "+chosen.size()+" item(ns) selecionado(s)");
    });
    loadFolder();
  }
  private void browseWithSelection(){note("Selecionados: "+chosen.size()+" (use Excluir ou Copiar/mover)");}
  private void loadFolder(){visible.clear();selectionBoxes.clear();run("Ler arquivos",()->request("GET",base()+"/contents/"+segments(folder)+(folder.isEmpty()?"?":"?")+"ref="+enc(branch),null),res->{JSONArray arr=res.optJSONArray("items");if(arr==null){note("Este caminho não é uma pasta.");return;}for(int i=0;i<arr.length();i++){JSONObject o=arr.getJSONObject(i);boolean dir=o.optString("type").equals("dir");String path=o.getString("path");Entry entry=new Entry(path,o.optString("sha"),dir);visible.put(path,entry);LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);body.addView(row);CheckBox box=new CheckBox(this);box.setChecked(chosen.contains(path));row.addView(box);selectionBoxes.add(box);box.setOnCheckedChangeListener((x,c)->{if(c)chosen.add(path);else chosen.remove(path);});Button b=new Button(this);b.setAllCaps(false);b.setText((dir?"📁 ":"📄 ")+o.getString("name"));b.setTextColor(Color.WHITE);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff263d5e));row.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{if(dir){folder=path;chosen.clear();browse();}else fileOptions(entry);});}if(arr.length()==0)note("Pasta vazia. Git não armazena pastas realmente vazias.");});}
  private void fileOptions(Entry e){new AlertDialog.Builder(this).setTitle(e.path).setItems(new String[]{"Visualizar / editar texto", "Renomear", "Excluir arquivo", "Copiar caminho", "⬇️ Baixar arquivo"},(d,w)->{if(w==0)openText(e);if(w==1)input("Novo nome (somente nome)",e.path.substring(e.path.lastIndexOf('/')+1),v->{if(v.contains("/")||v.isEmpty()){message("Nome inválido");return;}String parent=e.path.contains("/")?e.path.substring(0,e.path.lastIndexOf('/')+1):"";singleMove(e.path,parent+v,false);});if(w==2)confirm("Excluir "+e.path+"?",()->commitPaths(Collections.singletonList(e.path),"Excluir "+e.path));if(w==3){((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("path",e.path));}if(w==4)downloadSingleFile(e);}).show();}
  private void openText(Entry e){run("Abrir arquivo",()->{JSONObject o=request("GET",base()+"/contents/"+segments(e.path)+"?ref="+enc(branch),null);if(o.optLong("size")>500000)throw new IOException("Arquivo maior que 500 KB: utilize outra ferramenta de edição.");String encoded=o.optString("content","").replace("\n","");byte[] b=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);return new String(b,StandardCharsets.UTF_8);},v->edit(e.path,v));}
  private void edit(String path,String content){frame("✏️ Editar "+path);EditText e=new EditText(this);e.setText(content);e.setTextColor(Color.WHITE);e.setMinLines(12);e.setGravity(Gravity.TOP);e.setTypeface(Typeface.MONOSPACE);body.addView(e);button("💾 Salvar commit",()->confirm("Salvar "+path+" na branch "+branch+"?",()->run("Salvar arquivo",()->{String sha="";try{sha=request("GET",base()+"/contents/"+segments(path)+"?ref="+enc(branch),null).optString("sha","");}catch(Exception x){if(!x.getMessage().contains("404"))throw x;}JSONObject p=new JSONObject().put("message","Editar "+path).put("content",android.util.Base64.encodeToString(e.getText().toString().getBytes(StandardCharsets.UTF_8),android.util.Base64.NO_WRAP)).put("branch",branch);if(!sha.isEmpty())p.put("sha",sha);request("PUT",base()+"/contents/"+segments(path),p);return true;},x->browse())));button("Cancelar",this::browse);}
  private void removeSelected(){if(chosen.isEmpty()){message("Marque arquivos ou pastas primeiro.");return;}confirm("Excluir "+chosen.size()+" item(ns) e seus conteúdos de "+repo+"? Será registrado um commit. Antes de confirmar, confira se selecionou uma pasta: todos os arquivos dentro dela serão excluídos.",()->commitPaths(new ArrayList<>(chosen),"Excluir itens selecionados"));}
  private void commitPaths(List<String> paths,String msg){runBusy("Excluir arquivos selecionados",0,()->{busyTick("Verificando permissão e preparando exclusão...",0,0);TreeState state=treeState();JSONArray edits=new JSONArray();for(Entry e:state.files){for(String p:paths){if(e.path.equals(p)||e.path.startsWith(p+"/")){edits.put(new JSONObject().put("path",e.path).put("mode","100644").put("type","blob").put("sha",JSONObject.NULL));break;}}}if(edits.length()==0)throw new IOException("Nenhum arquivo correspondente.");busyTick("Criando commit para excluir "+edits.length()+" arquivo(s)...",0,0);commit(state,edits,msg);return true;},r->{chosen.clear();browse();});}
  private void transferSelected(){if(chosen.isEmpty()){message("Selecione arquivos/pastas.");return;}input("Pasta de destino (vazio = raiz)","",dest->{try{dest=safe(dest);if(dest.endsWith("/"))dest=dest.substring(0,dest.length()-1);String target=dest;new AlertDialog.Builder(this).setTitle("Operação").setItems(new String[]{"Copiar", "Mover"},(d,idx)->runBusy("Copiar/mover arquivos",0,()->{busyTick("Lendo estrutura do repositório...",0,0);TreeState s=treeState();JSONArray edits=new JSONArray();int changed=0;for(Entry f:s.files){for(String p:chosen){if(target.equals(p)||target.startsWith(p+"/"))throw new IOException("Não é possível mover/copiar uma pasta para dentro dela mesma.");if(f.path.equals(p)||f.path.startsWith(p+"/")){String leaf=p.contains("/")?p.substring(p.lastIndexOf('/')+1):p;String newPath=(target.isEmpty()?"":target+"/")+leaf+f.path.substring(p.length());if(newPath.equals(f.path)||newPath.startsWith(f.path+"/"))throw new IOException("Destino inválido.");edits.put(new JSONObject().put("path",newPath).put("mode",f.dir?"040000":"100644").put("type","blob").put("sha",f.sha));if(idx==1)edits.put(new JSONObject().put("path",f.path).put("mode","100644").put("type","blob").put("sha",JSONObject.NULL));changed++;break;}}}if(changed==0)throw new IOException("Nenhum arquivo para alterar.");busyTick("Criando commit de "+(idx==0?"cópia":"movimentação")+"...",0,0);commit(s,edits,(idx==0?"Copiar":"Mover")+" "+changed+" arquivos");return true;},r->{chosen.clear();browse();})).show();}catch(Exception e){fail(e);}});}
  private void singleMove(String old,String target,boolean copy){chosen.clear();chosen.add(old);run("Renomear arquivo",()->{TreeState s=treeState();JSONArray e=new JSONArray();for(Entry f:s.files)if(f.path.equals(old)){e.put(new JSONObject().put("path",target).put("mode","100644").put("type","blob").put("sha",f.sha));if(!copy)e.put(new JSONObject().put("path",old).put("mode","100644").put("type","blob").put("sha",JSONObject.NULL));}if(e.length()==0)throw new IOException("Arquivo não encontrado");commit(s,e,"Renomear "+old);return true;},r->{chosen.clear();browse();});}
  static class TreeState {String commit,tree;ArrayList<Entry> files=new ArrayList<>();}
  private TreeState treeState()throws Exception{TreeState s=new TreeState();JSONObject ref=request("GET",base()+"/git/ref/heads/"+segments(branch),null);s.commit=ref.getJSONObject("object").getString("sha");JSONObject c=request("GET",base()+"/git/commits/"+s.commit,null);s.tree=c.getJSONObject("tree").getString("sha");JSONObject tree=request("GET",base()+"/git/trees/"+s.tree+"?recursive=1",null);if(tree.optBoolean("truncated"))throw new IOException("Repositório muito grande: listagem Git truncada. Operação cancelada por segurança.");JSONArray arr=tree.getJSONArray("tree");for(int i=0;i<arr.length();i++){JSONObject o=arr.getJSONObject(i);if(o.optString("type").equals("blob"))s.files.add(new Entry(o.getString("path"),o.getString("sha"),false));}return s;}
  private void commit(TreeState state,JSONArray entries,String msg)throws Exception{
    checkWritePermission();
    JSONObject t;
    try{t=request("POST",base()+"/git/trees",new JSONObject().put("base_tree",state.tree).put("tree",entries));}
    catch(IOException e){
      if(e.getMessage().contains("HTTP 404")||e.getMessage().contains("HTTP 403"))
        throw new IOException("Falha em POST /git/trees: falta de acesso de escrita ao repositório "+user+"/"+repo+" ou token não autorizado. " +
          "Confira as permissões do token (Contents: Read and write) e marque este repositório em Only select repositories. Erro original: "+e.getMessage());
      throw e;
    }JSONObject c=request("POST",base()+"/git/commits",new JSONObject().put("message",msg).put("tree",t.getString("sha")).put("parents",new JSONArray().put(state.commit)));request("PATCH",base()+"/git/refs/heads/"+segments(branch),new JSONObject().put("sha",c.getString("sha")).put("force",false));}
  private void checkWritePermission()throws Exception{
    JSONObject metadata=request("GET",base(),null);
    JSONObject perms=metadata.optJSONObject("permissions");
    if(perms!=null && !perms.optBoolean("push",false) && !perms.optBoolean("admin",false) && !perms.optBoolean("maintain",false))
      throw new IOException("Conta/token sem permissão de gravação para "+user+"/"+repo+". O acesso de leitura não basta. " +
         "Edite o token fine-grained e inclua este repositório e Contents: Read and write.");
  }
  private void diagnosePermissions(){
    runBusy("Diagnosticar repositório",0,()->{
      busyTick("Consultando autenticação e permissões...",0,0);
      String loggedIn=request("GET","/user",null).getString("login");
      JSONObject details=request("GET",base(),null);
      JSONObject perms=details.optJSONObject("permissions");
      String permissions=perms==null?"Não informadas pela API":"Leitura: "+perms.optBoolean("pull")+
        "; Escrita (push): "+perms.optBoolean("push")+"; Administração: "+perms.optBoolean("admin");
      JSONObject ref=request("GET",base()+"/git/ref/heads/"+segments(branch),null);
      return "Conectado como: "+loggedIn+"\nRepositório: "+user+"/"+repo+
        "\nBranch: "+branch+"\nPermissões da conta: "+permissions+
        "\nRef atual: "+ref.optJSONObject("object").optString("sha", "—")+
        "\n\nSe tiver erro 404/403 ao criar árvore, revise: fine-grained token > Resource owner; Repository access; Contents: Read and write. " +
        "Se o repositório é novo, talvez precise adicioná-lo à seleção do token. Este diagnóstico NÃO faz upload de teste.";
    },this::message);
  }
  private void pick(int type,String mime){
    Intent in=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    in.setType(mime);
    in.addCategory(Intent.CATEGORY_OPENABLE);
    if(type==PICK_ZIP || type==PICK_NEW_REPO_ZIP)in.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/x-zip-compressed","application/octet-stream"});
    if(type==PICK_FILES)in.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
    startActivityForResult(in,type);
  }
  private void pickLocalFolder(){
    Intent in=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    startActivityForResult(in,PICK_FOLDER);
  }
  @Override protected void onActivityResult(int req,int result,Intent data){
    super.onActivityResult(req,result,data);
    if(result!=RESULT_OK || data==null)return;
    if(req==PICK_FILES){
      LinkedHashSet<Uri> uris=new LinkedHashSet<>();
      if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}
      if(data.getData()!=null)uris.add(data.getData());
      if(uris.isEmpty()){message("Nenhum arquivo selecionado.");return;}
      uploadSeveral(new ArrayList<>(uris));return;
    }
    Uri uri=data.getData();if(uri==null)return;
    if(req==PICK_SAVE_ARTIFACT || req==PICK_SAVE_REPO_ZIP || req==PICK_SAVE_SELECTED_ZIP || req==PICK_SAVE_ONE){
      if(pendingDownload==null){message("Seleção de download expirou. Tente novamente.");return;}
      DownloadJob job=pendingDownload;pendingDownload=null;
      saveRemoteFile(job,uri);return;
    }
    if(req==PICK_SAVE_REPO_TREE || req==PICK_SAVE_SELECTED_TREE){
      if(pendingDownload==null){message("Seleção de download expirou. Tente novamente.");return;}
      DownloadJob job=pendingDownload;pendingDownload=null;
      saveRemoteFolder(job,uri);return;
    }
    if(req==PICK_ZIP){uploadZip(uri);return;}
    if(req==PICK_NEW_REPO_ZIP){createRepoFromZip(uri);return;}
    if(req==PICK_FILE){
      String suggested=documentDisplayName(uri,"arquivo.bin");
      input("Nome no GitHub (pode incluir subpasta)",suggested,p->uploadSingle(uri,p));
    }
    if(req==PICK_FOLDER)uploadLocalFolder(uri);
  }
  private String documentDisplayName(Uri uri,String fallback){
    try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
      if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getString(0);
    }catch(Exception ignored){}
    return fallback;
  }
  private String segmentName(String name){
    if(name==null||name.trim().isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.indexOf('\n')>=0||name.indexOf('\r')>=0)
      throw new IllegalArgumentException("Nome de arquivo/pasta inválido: "+name);
    return name;
  }
  private String checkedPath(String p){
    String result=safe(p);
    if(result.isEmpty() || result.endsWith("/") || result.contains("//") || result.indexOf('\r')>=0 || result.indexOf('\n')>=0)
      throw new IllegalArgumentException("Caminho inválido: "+p);
    return result;
  }
  private String destination(String relative){return checkedPath((folder.isEmpty()?"":folder+"/")+relative);}
  private byte[] readLimited(InputStream stream,long limit)throws Exception{
    if(stream==null)throw new IOException("O Android não liberou acesso ao arquivo.");
    try(InputStream in=stream;ByteArrayOutputStream o=new ByteArrayOutputStream()){
      byte[] buf=new byte[16384];int n;
      while((n=in.read(buf))!=-1){o.write(buf,0,n);if(o.size()>limit)throw new IOException("Arquivo excede "+limit/1_000_000+" MB. Escolha um menor.");}
      return o.toByteArray();
    }
  }
  private void putFileContents(String path,byte[] bytes,String commitMessage)throws Exception{
    path=checkedPath(path);
    String existingSha="";
    try{JSONObject existing=request("GET",base()+"/contents/"+segments(path)+"?ref="+enc(branch),null);
      if(existing.optString("type").equals("dir"))throw new IOException("O destino já é uma pasta: "+path);
      existingSha=existing.optString("sha","");
    }catch(Exception ex){if(!String.valueOf(ex.getMessage()).contains("HTTP 404"))throw ex;}
    JSONObject put=new JSONObject().put("message",commitMessage).put("branch",branch)
      .put("content",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));
    if(!existingSha.isEmpty())put.put("sha",existingSha);
    request("PUT",base()+"/contents/"+segments(path),put);
  }
  private void createGitFolder(){
    input("Nome da pasta no GitHub (pode incluir subpastas)","Nova pasta",name->{
      try{String dest=destination(name);confirm("Criar a pasta "+dest+" no GitHub?\n\nComo o Git não armazena pasta vazia, será criado um arquivo .gitkeep dentro dela.",()->run("Criar pasta",()->{
        putFileContents(dest+"/.gitkeep",new byte[0],"Criar pasta "+dest);return true;
      },ok->browse()));}catch(Exception e){fail(e);}
    });
  }
  private void putBatch(LinkedHashMap<String,byte[]> files,String commitTitle)throws Exception{
    if(files.isEmpty())throw new IOException("Nenhum arquivo para enviar.");
    busyTick("Verificando permissão de escrita do token...",0,files.size()+3);
    checkWritePermission();
    busyTick("Lendo versão atual do repositório...",0,files.size()+3);
    TreeState state=treeState();JSONArray edits=new JSONArray();
    int num=0;
    for(Map.Entry<String,byte[]> f:files.entrySet()){
      String path=checkedPath(f.getKey());
      busyTick("Enviando: "+path,num,files.size()+3);
      String sha=request("POST",base()+"/git/blobs",new JSONObject()
        .put("content",android.util.Base64.encodeToString(f.getValue(),android.util.Base64.NO_WRAP))
        .put("encoding","base64")).getString("sha");
      edits.put(new JSONObject().put("path",path).put("mode","100644").put("type","blob").put("sha",sha));
      num++;
      busyTick("Enviado: "+path,num,files.size()+3);
    }
    busyTick("Arquivos enviados. Preparando commit único...",num,files.size()+3);
    commit(state,edits,commitTitle);
    busyTick("Commit publicado com sucesso!",files.size()+3,files.size()+3);
  }
  private void showBatchConfirm(LinkedHashMap<String,byte[]> files,String label){
    long total=0;int hidden=0;
    for(Map.Entry<String,byte[]> e:files.entrySet()){
      total+=e.getValue().length;
      for(String part:e.getKey().split("/"))if(part.startsWith(".")){hidden++;break;}
    }
    final long sum=total;
    confirm(label+"\nArquivos: "+files.size()+"; ocultos: "+hidden+"; total: "+sum/1024+" KB.\nDestino: "+user+"/"+repo+"/"+(folder.isEmpty()?"":folder+"/")+"\nArquivos existentes com o mesmo caminho serão SUBSTITUÍDOS. Continuar?",()->
      runBusy("Publicar arquivos no GitHub",files.size()+3,()->{putBatch(files,"Enviar "+files.size()+" arquivos pelo GitHub Manager 1.4");return true;},ok->{message("Publicado com sucesso! Confira Actions, se o projeto contém workflow.");browse();}));
  }
  private void uploadSingle(Uri uri,String pathRelative){
    try{String path=destination(pathRelative);
      run("Preparar arquivo",()->readLimited(getContentResolver().openInputStream(uri),MAX_FILE_BYTES),
        data->confirm("Publicar "+path+" ("+data.length+" bytes)? Substitui arquivo de mesmo nome.",()->runBusy("Enviar arquivo",0,()->{
          busyTick("Enviando "+path+"...",0,0);putFileContents(path,data,"Enviar "+path);return true;
        },ok->browse())));
    }catch(Exception e){fail(e);}
  }
  private void uploadSeveral(List<Uri> uris){
    runBusy("Ler "+uris.size()+" arquivos locais",uris.size(),()->{
      LinkedHashMap<String,byte[]> files=new LinkedHashMap<>();long total=0;
      if(uris.size()>MAX_BATCH_FILES)throw new IOException("Máximo de "+MAX_BATCH_FILES+" arquivos por envio.");
      int processed=0;for(Uri uri:uris){
        busyTick("Lendo arquivos locais...",processed,uris.size());
        String name=segmentName(documentDisplayName(uri,"arquivo"));String dest=destination(name);
        if(files.containsKey(dest))throw new IOException("Arquivos com nomes iguais: "+name+". Renomeie antes de enviar.");
        byte[] data=readLimited(getContentResolver().openInputStream(uri),MAX_FILE_BYTES);
        total+=data.length;
        if(total>MAX_BATCH_BYTES)throw new IOException("Máximo de 18 MB por lote.");
        files.put(dest,data);processed++;busyTick("Preparado: "+name,processed,uris.size());
      }
      return files;
    },files->showBatchConfirm(files,"Enviar arquivos selecionados"));
  }
  private void uploadLocalFolder(Uri treeUri){
    runBusy("Ler pasta do celular",0,()->{
      String treeId=DocumentsContract.getTreeDocumentId(treeUri);
      Uri rootDoc=DocumentsContract.buildDocumentUriUsingTree(treeUri,treeId);
      String rootName=segmentName(documentDisplayName(rootDoc,"MinhaPasta"));
      LinkedHashMap<String,byte[]> files=new LinkedHashMap<>();
      long[] total=new long[]{0};
      crawlLocalFolder(treeUri,treeId,rootName,files,total,0);
      if(files.isEmpty())throw new IOException("Pasta não contém arquivos. Pastas vazias não existem no Git.");
      return files;
    },files->showBatchConfirm(files,"Enviar PASTA inteira, mantendo o nome dela e suas subpastas"));
  }
  private void crawlLocalFolder(Uri treeUri,String documentId,String relative,LinkedHashMap<String,byte[]> files,long[] total,int depth)throws Exception{
    if(depth>24)throw new IOException("Estrutura de pastas profunda demais (máximo 24 níveis).");
    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,documentId);
    try(Cursor c=getContentResolver().query(children,new String[]{
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
      if(c==null)throw new IOException("Não foi possível consultar a pasta no Android.");
      while(c.moveToNext()){
        String id=c.getString(0),name=segmentName(c.getString(1)),mime=c.getString(2);
        String childPath=checkedPath(relative+"/"+name);
        if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){
          crawlLocalFolder(treeUri,id,childPath,files,total,depth+1);
        }else{
          if(files.size()>=MAX_BATCH_FILES)throw new IOException("Pasta contém mais de "+MAX_BATCH_FILES+" arquivos.");
          Uri uri=DocumentsContract.buildDocumentUriUsingTree(treeUri,id);
          byte[] b=readLimited(getContentResolver().openInputStream(uri),MAX_FILE_BYTES);
          total[0]+=b.length;
          if(total[0]>MAX_BATCH_BYTES)throw new IOException("Pasta excede o limite de 18 MB por envio.");
          String dest=destination(childPath);
          if(files.put(dest,b)!=null)throw new IOException("Caminho repetido: "+dest);
          if(files.size()%5==0)busyTick("Lidos "+files.size()+" arquivos da pasta...",0,0);
        }
      }
    }
  }
  private LinkedHashMap<String,byte[]> readZipFiles(Uri uri,boolean repoRoot)throws Exception{
    LinkedHashMap<String,byte[]> files=new LinkedHashMap<>();long total=0;
    InputStream source=getContentResolver().openInputStream(uri);
    if(source==null)throw new IOException("Não foi possível abrir o ZIP no celular.");
    try(ZipInputStream z=new ZipInputStream(source)){
      ZipEntry e;byte[] buf=new byte[8192];
      while((e=z.getNextEntry())!=null){
        if(e.isDirectory()){z.closeEntry();continue;}
        if(files.size()>=MAX_BATCH_FILES)throw new IOException("Limite de "+MAX_BATCH_FILES+" arquivos por ZIP.");
        String raw=e.getName();while(raw.startsWith("./"))raw=raw.substring(2);
        String path=repoRoot?checkedPath(raw):destination(raw);
        ByteArrayOutputStream out=new ByteArrayOutputStream();int n;
        while((n=z.read(buf))!=-1){
          out.write(buf,0,n);total+=n;
          if(out.size()>MAX_FILE_BYTES)throw new IOException("Arquivo excede 5 MB: "+path);
          if(total>MAX_BATCH_BYTES)throw new IOException("ZIP excede 18 MB descompactados.");
        }
        if(files.put(path,out.toByteArray())!=null)throw new IOException("Caminho repetido no ZIP: "+path);
        if(files.size()%5==0)busyTick("Lidos "+files.size()+" arquivos do ZIP...",0,0);
        z.closeEntry();
      }
    }
    if(files.isEmpty())throw new IOException("O ZIP não contém arquivos.");
    return files;
  }
  private void uploadZip(Uri uri){
    runBusy("Preparar arquivos do ZIP",0,()->readZipFiles(uri,false),
      files->showBatchConfirm(files,"Enviar ZIP em UM commit, incluindo arquivos ocultos"));
  }

  // Fluxo completo: selecionar ZIP -> escolher nome e visibilidade -> criar repo -> publicar arquivos.
  // O novo repositório só é criado DEPOIS de o ZIP passar na validação local.
  private void createRepoFromZip(Uri uri){
    if(token.isEmpty()){message("Conecte-se com seu token do GitHub antes de criar um repositório.");return;}
    runBusy("Conferir ZIP antes de criar repositório",0,()->readZipFiles(uri,true),
      files->showCreateRepoFromZipDialog(files,documentDisplayName(uri,"projeto.zip")));
  }
  private void showCreateRepoFromZipDialog(LinkedHashMap<String,byte[]> files,String fileName){
    int hidden=0;long bytes=0;boolean hasWorkflow=false;boolean hasIndex=false;
    for(Map.Entry<String,byte[]> file:files.entrySet()){
      bytes+=file.getValue().length;
      String path=file.getKey();
      if(path.startsWith(".github/workflows/") && (path.endsWith(".yml")||path.endsWith(".yaml")))hasWorkflow=true;
      if(path.equalsIgnoreCase("index.html"))hasIndex=true;
      for(String part:path.split("/"))if(part.startsWith(".")){hidden++;break;}
    }
    final boolean workflow=hasWorkflow;
    LinearLayout content=new LinearLayout(this);content.setOrientation(1);content.setPadding(34,16,34,4);
    TextView help=new TextView(this);
    help.setText("ZIP: "+fileName+"\n"+files.size()+" arquivos ("+(bytes/1024)+" KB); "+hidden+" ocultos.\n"+
      (workflow?"✅ Contém workflow de GitHub Actions.\n":"⚠️ Sem workflow .github/workflows/*.yml; o APK não será compilado automaticamente.\n")+
      (hasIndex?"🌐 Contém index.html. GitHub Pages não será ativado sem sua autorização.\n":"")+
      "Os arquivos serão publicados na RAIZ do novo repositório, sem extrair no celular.");
    help.setTextSize(14);help.setTextColor(0xffd0dcf0);content.addView(help);
    String generated=suggestedRepoName(fileName);
    TextView autoName=new TextView(this);
    autoName.setText("Nome automático: "+generated);
    autoName.setTextColor(Color.WHITE);autoName.setTextSize(17);autoName.setPadding(0,12,0,6);
    content.addView(autoName);
    EditText rename=new EditText(this);rename.setSingleLine(true);rename.setText(generated);
    rename.setSelectAllOnFocus(true);rename.setVisibility(View.GONE);
    content.addView(rename);
    CheckBox editName=new CheckBox(this);editName.setText("✏️ Alterar nome manualmente (opcional)");
    editName.setTextColor(Color.WHITE);
    rename.setTextColor(Color.WHITE);
    editName.setOnCheckedChangeListener((x,checked)->rename.setVisibility(checked?View.VISIBLE:View.GONE));
    content.addView(editName);
    RadioGroup privacy=new RadioGroup(this);privacy.setOrientation(RadioGroup.VERTICAL);
    RadioButton priv=new RadioButton(this);priv.setText("🔒 Privado (recomendado)");privacy.addView(priv);
    RadioButton pub=new RadioButton(this);pub.setText("🌍 Público (qualquer pessoa pode ver os arquivos)");privacy.addView(pub);
    priv.setTextColor(Color.WHITE);pub.setTextColor(Color.WHITE);
    priv.setChecked(true);content.addView(privacy);
    TextView explain=new TextView(this);
    explain.setText("Somente selecione Público ou Privado. O nome vem do ZIP, sem acentos, símbolos ou espaços. " +
       "Se o nome já existir, outro nome será sugerido antes de criar. Token: Repository creation (write), Contents (write), Workflows (write) para YAML.");
    explain.setTextSize(12);explain.setTextColor(0xffb9d0ec);content.addView(explain);
    AlertDialog dialog=new AlertDialog.Builder(this)
      .setTitle("🚀 ZIP → novo repositório")
      .setView(content).setNegativeButton("Cancelar",null)
      .setPositiveButton("Criar e publicar",null).create();
    dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
      String newName=editName.isChecked()?rename.getText().toString().trim():generated;
      if(!newName.matches("[A-Za-z0-9._-]{1,100}") || newName.equals(".") || newName.equals("..")){
        rename.setVisibility(View.VISIBLE);rename.setError("Use 1 a 100 caracteres: letras, números, . _ -");return;
      }
      boolean isPrivate=priv.isChecked();
      dialog.dismiss();
      checkExistingRepoBeforePublish(newName,isPrivate,files,workflow);
    }));
    dialog.show();
  }
  private void checkExistingRepoBeforePublish(String wanted,boolean priv,LinkedHashMap<String,byte[]> files,boolean workflow){
    String owner=accountLogin;
    if(owner.isEmpty()){message("Valide o token primeiro; não consegui identificar o nome da sua conta.");return;}
    run("Verificar nome disponível",()->{
      String candidate=wanted;
      for(int index=2;index<=25;index++){
        try{
          request("GET","/repos/"+enc(owner)+"/"+enc(candidate),null);
          String suffix="-"+index;
          candidate=wanted.substring(0,Math.min(wanted.length(),100-suffix.length()))+suffix;
        }catch(IOException e){
          if(String.valueOf(e.getMessage()).contains("HTTP 404"))return candidate;
          throw e;
        }
      }
      throw new IOException("Muitos repositórios com nomes semelhantes. Escolha outro nome manualmente.");
    },proposed->confirm("ZIP: "+files.size()+" arquivos.\nNome: "+proposed+
      (proposed.equals(wanted)?"":"\n(O nome original já existe; não vamos sobrescrever.)")+
      "\nVisibilidade: "+(priv?"PRIVADO":"PÚBLICO")+
      "\n\nCriar e publicar agora?",()->createAndPublish(proposed,priv,files,workflow)));
  }
  private String suggestedRepoName(String filename){
    String name=filename.replaceFirst("(?i)\\.zip$","").trim();
    String normalized=java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFD);
    normalized=normalized.replaceAll("\\p{M}","")
      .replaceAll("[^A-Za-z0-9._-]+","-").replaceAll("-+","-")
      .replaceAll("^[._-]+|[._-]+$","");
    if(normalized.length()>90)normalized=normalized.substring(0,90).replaceAll("[._-]+$","");
    return normalized.isEmpty()?"meu-projeto":normalized;
  }

  private void createAndPublish(String newName,boolean isPrivate,LinkedHashMap<String,byte[]> files,boolean hasWorkflow){
    runBusy("Criar repositório e enviar ZIP",files.size()+5,()->{
      busyTick("1/3 — Criando novo repositório no GitHub...",0,files.size()+5);
      JSONObject p=new JSONObject().put("name",newName).put("private",isPrivate).put("auto_init",true)
        .put("description","Projeto enviado pelo GitHub Manager Android 1.4");
      JSONObject created;
      try{created=request("POST","/user/repos",p);}
      catch(IOException e){
        String msg=String.valueOf(e.getMessage());
        if(msg.contains("HTTP 403"))throw new IOException("O token não tem permissão para criar repositórios. " +
          "No GitHub, autorize 'Repository creation: Read and write' (ou 'Administration: Read and write'). Erro: "+msg);
        if(msg.contains("HTTP 422"))throw new IOException("Não consegui criar '"+newName+"'. O nome pode já existir nesta conta. Erro: "+msg);
        throw e;
      }
      String own=created.getJSONObject("owner").getString("login");
      String actual=created.getString("name");
      String defaultBranch=created.optString("default_branch","main");
      if(defaultBranch.isEmpty() || defaultBranch.equals("null"))defaultBranch="main";
      user=own;repo=actual;branch=defaultBranch;folder="";chosen.clear();
      busyTick("2/3 — Repositório "+own+"/"+actual+" criado. Aguardando a branch...",1,files.size()+5);
      try{
        // GitHub pode levar um instante para criar o primeiro commit README da branch.
        for(int attempt=0;attempt<4;attempt++){
          try{request("GET",base()+"/git/ref/heads/"+segments(branch),null);break;}
          catch(IOException ex){if(attempt==3)throw ex;Thread.sleep(650L*(attempt+1));}
        }
        busyTick("2/3 — Publicando arquivos na branch "+branch+"...",1,files.size()+5);
        putBatch(files,"Importar "+files.size()+" arquivos do ZIP automaticamente");
      }
      catch(Exception e){
        throw new IOException("O repositório "+own+"/"+actual+" foi CRIADO, mas não foi possível publicar todos os arquivos. " +
          "Ele continua na sua conta; abra-o pelo GitHub Manager e tente Enviar ZIP. " +
          "Se o token estiver restrito a repositórios selecionados, adicione o novo repositório em Repository access. " +
          "Falha: "+e.getMessage(),e);
      }
      busyTick("3/3 — Publicação concluída!",files.size()+5,files.size()+5);
      return actual;
    },ok->new AlertDialog.Builder(this).setTitle("✅ Repositório criado e ZIP enviado")
      .setMessage("Publicado em "+user+"/"+repo+" ("+files.size()+" arquivos).\n"+
        (hasWorkflow?"Se o GitHub Actions estiver habilitado, a compilação poderá começar automaticamente na branch "+branch+".":
          "Sem workflow no ZIP. O GitHub não gera um APK automaticamente.")+
        "\n\nO repositório pode ser aberto agora.")
      .setNegativeButton("Ver arquivos",(d,w)->browse())
      .setPositiveButton("Abrir Actions",(d,w)->actions())
      .show());
  }
  private void repoOptions(){frame("⚙️ "+user+"/"+repo);button("← Voltar aos arquivos",this::browse);button("✏️ Renomear repositório",()->input("Novo nome",repo,n->confirm("Renomear "+repo+" para "+n+"?",()->run("Renomear",()->request("PATCH",base(),new JSONObject().put("name",n)),r->{repo=r.getString("name");browse();}))));button("📝 Alterar descrição",()->input("Descrição do repositório","",desc->run("Alterar descrição",()->request("PATCH",base(),new JSONObject().put("description",desc)),r->browse())));button("🌐 Ativar GitHub Pages (branch / raiz)",()->confirm("Publicar o conteúdo da branch "+branch+" na raiz pelo GitHub Pages? O repositório precisa de index.html, e Pages pode exigir que seja público conforme o plano.",()->run("Ativar Pages",()->{JSONObject payload=new JSONObject().put("source",new JSONObject().put("branch",branch).put("path","/"));try{return request("POST",base()+"/pages",payload);}catch(Exception x){if(x.getMessage().contains("409")||x.getMessage().contains("422"))return request("PUT",base()+"/pages",payload);throw x;}},r->message("Configuração enviada. Confira a publicação no GitHub Pages."))));button("⚙️ Consultar execução de Actions",this::actions);
    button("🔎 Consultar status da compilação",this::checkLatestRun);
    button("📲 Baixar APK sem abrir navegador",this::latestApk);
    button("📦 Baixar repositório ZIP completo",this::downloadRepoZip);
    button("🌐 Abrir repositório dentro do app",()->inAppPage("https://github.com/"+user+"/"+repo,"Repositório"));
    button("🔐 Diagnosticar permissões do token",this::diagnosePermissions);button("🗑 Excluir repositório inteiro",()->input("Digite exatamente o nome do repositório para excluir", "",n->{if(!n.equals(repo)){message("Nome não confere. Nada excluído.");return;}confirm("ATENÇÃO: excluir TODO o repositório "+user+"/"+repo+"? Esta ação é destrutiva.",()->run("Excluir repositório",()->request("DELETE",base(),null),r->repos()));}));}
  private void actions(){
    frame("⚙️ Actions — "+repo);
    button("← Voltar",this::repoOptions);
    button("🌐 Ver página Actions aqui dentro",()->inAppPage("https://github.com/"+user+"/"+repo+"/actions","GitHub Actions"));
    button("🌍 Abrir Actions no navegador",()->openOutside("https://github.com/"+user+"/"+repo+"/actions"));
    button("🔎 Verificar última compilação",this::checkLatestRun);
    button("📲 Baixar último APK diretamente",this::latestApk);
    button("📦 Listar artefatos disponíveis",this::showRepositoryArtifacts);
    button("🔄 Atualizar execuções",this::actions);
    run("Consultar últimas execuções",()->request("GET",base()+"/actions/runs?per_page=20",null).getJSONArray("workflow_runs"),arr->{
      for(int i=0;i<arr.length();i++){
        JSONObject o=arr.getJSONObject(i);
        String url=o.optString("html_url","");
        String title=o.optString("name","Workflow")+" #"+o.optInt("run_number")+
          " — "+(o.isNull("conclusion")?o.optString("status",""):o.optString("conclusion",""));
        button(title,()->new AlertDialog.Builder(this).setTitle(title)
          .setItems(new String[]{"📥 Ver/baixar artefatos desta execução", "🌐 Abrir aqui dentro (WebView)","🌍 Abrir no navegador"},(d,w)->{
            if(w==0)showRunArtifacts(o.optLong("id"),title);else if(w==1)inAppPage(url,title);else openOutside(url);
          }).show());
      }
      if(arr.length()==0)note("Nenhuma execução encontrada.");
    });
  }
  // ======================== DOWNLOADS v1.4 ========================
  // Download via REST API, sem depender do botão de download exibido pelo site na WebView.
  // Os links temporários (302) recebem requisição sem token; nunca enviar token ao CDN.
  private boolean trustedRedirect(String host){
    host=host.toLowerCase(Locale.ROOT);
    return host.equals("github.com") || host.endsWith(".github.com") ||
      host.equals("githubusercontent.com") || host.endsWith(".githubusercontent.com") ||
      host.endsWith(".blob.core.windows.net") || host.endsWith(".amazonaws.com") ||
      host.endsWith(".azureedge.net");
  }
  private RemoteStream openRemoteStream(String endpoint)throws Exception{
    URL next=new URL("https://api.github.com"+endpoint);
    for(int redirect=0;redirect<7;redirect++){
      if(!next.getProtocol().equalsIgnoreCase("https") || !trustedRedirect(next.getHost()))
        throw new IOException("Destino não confiável para o download do GitHub: "+next.getHost());
      HttpURLConnection c=(HttpURLConnection)next.openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(22000);c.setReadTimeout(60000);
      c.setRequestProperty("User-Agent","GitHubManagerAndroid/1.4");
      boolean api=next.getHost().equalsIgnoreCase("api.github.com");
      if(api){
        c.setRequestProperty("Accept","application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
        c.setRequestProperty("Authorization","Bearer "+token);
      }
      int code=c.getResponseCode();
      if(code==301||code==302||code==303||code==307||code==308){
        String location=c.getHeaderField("Location");
        if(location==null||location.isEmpty()){c.disconnect();throw new IOException("Redirecionamento sem destino.");}
        URL target=new URL(next,location);c.disconnect();next=target;continue;
      }
      if(code!=200){
        String detail="";
        try(InputStream err=c.getErrorStream()){
          if(err!=null){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] chunk=new byte[256];int n;
            while(o.size()<1000 && (n=err.read(chunk))!=-1)o.write(chunk,0,Math.min(n,1000-o.size()));
            detail=o.toString("UTF-8");}
        }catch(Exception ignored){}
        c.disconnect();
        throw new IOException("GitHub download HTTP "+code+" "+detail+
          (code==403?". Confira Actions: Read no token; para ZIP do repositório, Contents: Read.":
            code==410?". Artefato expirado.":""));
      }
      return new RemoteStream(c,c.getInputStream(),c.getContentLengthLong());
    }
    throw new IOException("Excesso de redirecionamentos do GitHub.");
  }
  private void checkLatestRun(){
    run("Consultar última execução",()->{
      JSONArray runs=request("GET",base()+"/actions/runs?per_page=1&branch="+enc(branch),null).getJSONArray("workflow_runs");
      return runs.length()>0?runs.getJSONObject(0):null;
    },r->{
      if(r==null){message("Não há compilação no GitHub Actions desta branch.");return;}
      String statusText=r.optString("status","—"),conclusion=r.optString("conclusion","—");
      String text="Workflow: "+r.optString("name","Actions")+" #"+r.optInt("run_number")+
        "\nStatus: "+statusText+"\nResultado: "+conclusion+
        "\nBranch: "+r.optString("head_branch",branch)+
        "\nCriado: "+r.optString("created_at","—")+
        "\nSe estiver concluído com sucesso, você pode baixar o APK.";
      new AlertDialog.Builder(this).setTitle("🔎 Estado da compilação").setMessage(text)
        .setNegativeButton("Fechar",null).setNeutralButton("Ver artefatos",(d,w)->showRunArtifacts(r.optLong("id"),r.optString("name","Actions")))
        .setPositiveButton("Abrir Actions",(d,w)->actions()).show();
    });
  }
  private void showRunArtifacts(long runId,String heading){
    if(runId<=0){message("Identificação da execução indisponível.");return;}
    run("Consultar artefatos",()->{
      JSONArray arr=request("GET",base()+"/actions/runs/"+runId+"/artifacts?per_page=100",null).getJSONArray("artifacts");
      ArrayList<JSONObject> list=new ArrayList<>();
      for(int i=0;i<arr.length();i++){JSONObject a=arr.getJSONObject(i);if(!a.optBoolean("expired",false))list.add(a);}
      return list;
    },list->showArtifactsDialog(list,heading));
  }
  private void showRepositoryArtifacts(){
    run("Listar artefatos do repositório",()->{
      JSONArray arr=request("GET",base()+"/actions/artifacts?per_page=100",null).getJSONArray("artifacts");
      ArrayList<JSONObject> result=new ArrayList<>();
      for(int i=0;i<arr.length();i++){JSONObject a=arr.getJSONObject(i);if(!a.optBoolean("expired",false))result.add(a);}
      return result;
    },arr->showArtifactsDialog(arr,"Artefatos disponíveis"));
  }
  private void latestApk(){
    runBusy("Localizar APK do GitHub Actions",0,()->{
      ArtifactFound found=new ArtifactFound();
      busyTick("Consultando execuções...",0,0);
      JSONArray runs=request("GET",base()+"/actions/runs?per_page=25&branch="+enc(branch),null).getJSONArray("workflow_runs");
      for(int i=0;i<runs.length();i++){
        JSONObject r=runs.getJSONObject(i);
        if(found.latest==null)found.latest=r;
        if(!"success".equalsIgnoreCase(r.optString("conclusion")))continue;
        busyTick("Conferindo artefatos da compilação #"+r.optInt("run_number"),0,0);
        JSONArray arr=request("GET",base()+"/actions/runs/"+r.getLong("id")+"/artifacts?per_page=100",null).getJSONArray("artifacts");
        ArrayList<JSONObject> matches=new ArrayList<>();
        for(int j=0;j<arr.length();j++){
          JSONObject a=arr.getJSONObject(j);
          if(!a.optBoolean("expired",false) && a.optString("name","").toLowerCase(Locale.ROOT).contains("apk"))matches.add(a);
        }
        if(!matches.isEmpty()){found.success=r;found.artifacts=matches;break;}
        if(i>=11)break;
      }
      return found;
    },res->{
      if(res.artifacts.isEmpty()){
        String last=res.latest==null?"Nenhuma execução encontrada.":
          "Última execução #"+res.latest.optInt("run_number")+" — "+res.latest.optString("status")+
          " / "+res.latest.optString("conclusion","ainda em execução");
        message("Não encontrei um APK disponível na branch "+branch+".\n"+last+
          "\nConfira Actions (verde) e se o workflow publicou um artefato com 'APK' no nome. " +
          "Artefatos podem expirar. O token precisa de Actions: Read.");return;
      }
      showArtifactsDialog(res.artifacts,"APK disponível — compilação #"+res.success.optInt("run_number"));
    });
  }
  private void showArtifactsDialog(List<JSONObject> artifacts,String title){
    if(artifacts.isEmpty()){message("Nenhum artefato disponível para esta execução. Pode estar expirado ou o build não terminou.");return;}
    String[] labels=new String[artifacts.size()];
    for(int i=0;i<artifacts.size();i++){
      JSONObject a=artifacts.get(i);long size=a.optLong("size_in_bytes",0);
      labels[i]="📦 "+a.optString("name","artefato")+"  ("+formatBytes(size)+")";
    }
    new AlertDialog.Builder(this).setTitle(title).setItems(labels,(d,idx)->{
      JSONObject a=artifacts.get(idx);boolean apk=a.optString("name","").toLowerCase(Locale.ROOT).contains("apk");
      String[] options=apk?new String[]{"📲 Salvar APK pronto (.apk)","📦 Salvar artefato ZIP (.zip)"}:
        new String[]{"📦 Salvar artefato ZIP (.zip)"};
      new AlertDialog.Builder(this).setTitle(a.optString("name","Artefato"))
        .setItems(options,(dialog,which)->{
          DownloadJob job=new DownloadJob();job.kind=1;job.artifactId=a.optLong("id");
          job.owner=user;job.repo=repo;job.branch=branch;job.extractApk=apk&&which==0;
          job.name=filenameForSaving(a.optString("name","APK"),job.extractApk?".apk":".zip");
          askFileDestination(job,job.extractApk?"application/vnd.android.package-archive":"application/zip",PICK_SAVE_ARTIFACT);
        }).show();
    }).show();
  }
  private String filenameForSaving(String name,String extension){
    String sanitized=name.replaceAll("[\\\\/\\r\\n]+","-")
      .replaceAll("[^\\p{L}\\p{N}._ -]","-").trim();
    if(sanitized.isEmpty())sanitized="Download";
    if(sanitized.length()>110)sanitized=sanitized.substring(0,110);
    if(!sanitized.toLowerCase(Locale.ROOT).endsWith(extension))sanitized+=extension;
    return sanitized;
  }
  private static String formatBytes(long bytes){
    if(bytes<1024)return bytes+" B";
    if(bytes<1048576)return String.format(Locale.US,"%.1f KB",bytes/1024.0);
    return String.format(Locale.US,"%.1f MB",bytes/1048576.0);
  }
  private void askFileDestination(DownloadJob job,String mime,int requestCode){
    pendingDownload=job;
    Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(mime);
    i.putExtra(Intent.EXTRA_TITLE,job.name);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    startActivityForResult(i,requestCode);
  }
  private void askFolderDestination(DownloadJob job,int requestCode){
    pendingDownload=job;
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    startActivityForResult(i,requestCode);
  }
  private void downloadRepoZip(){
    DownloadJob job=new DownloadJob();job.kind=2;job.owner=user;job.repo=repo;job.branch=branch;
    job.name=filenameForSaving(repo+"-"+branch,".zip");
    askFileDestination(job,"application/zip",PICK_SAVE_REPO_ZIP);
  }
  private void downloadRepoFolder(){
    confirm("O repositório completo será baixado e extraído para uma NOVA PASTA dentro do local selecionado. " +
      "Projetos grandes podem ocupar muito espaço e consumir sua franquia de internet. Continuar?",()->{
      DownloadJob job=new DownloadJob();job.kind=2;job.owner=user;job.repo=repo;job.branch=branch;
      askFolderDestination(job,PICK_SAVE_REPO_TREE);
    });
  }
  private void downloadSingleFile(Entry e){
    DownloadJob job=new DownloadJob();job.kind=3;job.owner=user;job.repo=repo;job.branch=branch;
    job.name=filenameForSaving(e.path.substring(e.path.lastIndexOf('/')+1),"");job.sha=e.sha;
    askFileDestination(job,"application/octet-stream",PICK_SAVE_ONE);
  }
  private void downloadSelectedZip(){
    if(chosen.isEmpty()){message("Marque ao menos um arquivo ou pasta para baixar.");return;}
    DownloadJob job=selectedJob();job.name=filenameForSaving(repo+"-selecionados",".zip");
    askFileDestination(job,"application/zip",PICK_SAVE_SELECTED_ZIP);
  }
  private void downloadSelectedFolder(){
    if(chosen.isEmpty()){message("Marque arquivos ou pastas para baixar.");return;}
    confirm("Baixar "+chosen.size()+" item(ns) selecionados para uma nova pasta no celular? " +
      "A estrutura de subpastas será preservada.",()->askFolderDestination(selectedJob(),PICK_SAVE_SELECTED_TREE));
  }
  private void downloadCurrentFolderZip(){
    DownloadJob job=new DownloadJob();job.kind=4;job.owner=user;job.repo=repo;job.branch=branch;
    job.paths=new ArrayList<>();job.paths.add(folder.isEmpty()?"":folder+"/");
    job.baseFolder=folder;
    job.name=filenameForSaving(repo+"-"+(folder.isEmpty()?"raiz":folder.replace('/','-')),".zip");
    askFileDestination(job,"application/zip",PICK_SAVE_SELECTED_ZIP);
  }
  private DownloadJob selectedJob(){
    DownloadJob job=new DownloadJob();job.kind=4;job.owner=user;job.repo=repo;job.branch=branch;
    job.paths=new ArrayList<>(chosen);job.baseFolder=folder;return job;
  }
  private String requestBase(DownloadJob job){return "/repos/"+enc(job.owner)+"/"+enc(job.repo);}
  private byte[] readBlob(DownloadJob job,String sha)throws Exception{
    JSONObject blob=request("GET",requestBase(job)+"/git/blobs/"+enc(sha),null);
    if(!"base64".equalsIgnoreCase(blob.optString("encoding")))
      throw new IOException("O GitHub não retornou base64 para um arquivo. Use o download de repositório completo.");
    long length=blob.optLong("size",0);
    if(length>MAX_SINGLE_EXPORT_FILE_BYTES)throw new IOException("Arquivo maior que o limite individual ("+formatBytes(MAX_SINGLE_EXPORT_FILE_BYTES)+"): "+formatBytes(length)+". Para arquivos grandes, baixe o repositório completo.");
    return android.util.Base64.decode(blob.getString("content"),android.util.Base64.DEFAULT);
  }
  private ArrayList<Entry> exportFiles(DownloadJob job)throws Exception{
    JSONObject obj=request("GET",requestBase(job)+"/git/trees/"+segments(job.branch)+"?recursive=1",null);
    if(obj.optBoolean("truncated",false))throw new IOException("A lista de arquivos foi truncada pelo GitHub. Baixe o repositório completo.");
    JSONArray tree=obj.getJSONArray("tree");ArrayList<Entry> hits=new ArrayList<>();
    for(int i=0;i<tree.length();i++){
      JSONObject item=tree.getJSONObject(i);
      if(!"blob".equals(item.optString("type")))continue;
      String name=item.getString("path");
      for(String wanted:job.paths){
        boolean match=wanted.isEmpty() || name.equals(wanted) ||
          (wanted.endsWith("/")?name.startsWith(wanted):name.startsWith(wanted+"/"));
        if(match){hits.add(new Entry(name,item.getString("sha"),false));break;}
      }
    }
    if(hits.isEmpty())throw new IOException("Nenhum arquivo encontrado nos itens selecionados.");
    if(hits.size()>MAX_EXPORT_FILES)throw new IOException("Mais de "+MAX_EXPORT_FILES+
      " arquivos; baixe o repositório completo em ZIP.");
    return hits;
  }
  private String exportRelative(DownloadJob job,String path){
    // Dentro da pasta atual, salvar o caminho relativo; selecionando diretório manter seu nome.
    if(job.baseFolder!=null && !job.baseFolder.isEmpty() && path.startsWith(job.baseFolder+"/"))
      return checkedPath(path.substring(job.baseFolder.length()+1));
    return checkedPath(path);
  }
  private void saveRemoteFile(DownloadJob job,Uri output){
    runBusy("Baixar "+job.name,0,()->{
      busyTick("Conectando ao GitHub...",0,0);
      try(OutputStream dest=getContentResolver().openOutputStream(output,"w")){
        if(dest==null)throw new IOException("Não consegui abrir o destino escolhido.");
        if(job.kind==1){
          String endpoint=requestBase(job)+"/actions/artifacts/"+job.artifactId+"/zip";
          try(RemoteStream rs=openRemoteStream(endpoint)){
            if(job.extractApk){
              try(ZipInputStream z=new ZipInputStream(rs.input)){
                ZipEntry ent;boolean copied=false;
                while((ent=z.getNextEntry())!=null){
                  if(!ent.isDirectory() && ent.getName().toLowerCase(Locale.ROOT).endsWith(".apk")){
                    copyProgress(z,dest,"Extraindo APK: "+ent.getName(),800_000_000L);
                    copied=true;z.closeEntry();break;
                  }
                  z.closeEntry();
                }
                if(!copied)throw new IOException("O artefato não contém arquivo .apk. Tente salvar o ZIP completo.");
              }
            }else copyProgress(rs.input,dest,"Baixando ZIP do APK",800_000_000L);
          }
        }else if(job.kind==2){
          try(RemoteStream rs=openRemoteStream(requestBase(job)+"/zipball/"+segments(job.branch))){
            copyProgress(rs.input,dest,"Baixando repositório ZIP",800_000_000L);
          }
        }else if(job.kind==3){
          byte[] data=readBlob(job,job.sha);dest.write(data);
          busyTick("Arquivo salvo: "+formatBytes(data.length),0,0);
        }else if(job.kind==4){
          ArrayList<Entry> files=exportFiles(job);
          try(ZipOutputStream zip=new ZipOutputStream(dest)){
            long total=0;int idx=0;
            for(Entry entry:files){
              byte[] data=readBlob(job,entry.sha);
              total+=data.length;
              if(total>MAX_EXPORT_BYTES)throw new IOException("Exportação excedeu "+formatBytes(MAX_EXPORT_BYTES));
              String name=exportRelative(job,entry.path);
              zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();idx++;
              busyTick("ZIP: "+name,idx,files.size());
            }
          }
        }else throw new IOException("Tipo de download desconhecido");
        // ZipOutputStream encerra seu fluxo ao fechar; nao tentar flush depois do fechamento.
        if(job.kind!=4)dest.flush();
      }
      return true;
    },ok->{
      if(job.kind==1 && job.extractApk){
        new AlertDialog.Builder(this).setTitle("✅ APK salvo")
          .setMessage("Salvo como "+job.name+". Você pode instalar agora ou abrir o arquivo depois na pasta escolhida.")
          .setNegativeButton("Concluir",null)
          .setPositiveButton("📲 Abrir para instalar",(d,w)->openDownloadedApk(output)).show();
      }else message("✅ Download salvo!\n"+job.name+"\nLocal: pasta escolhida no Android."+
          (job.kind==1?"\n\nUm arquivo ZIP de artefato deve ser extraído antes de instalar o APK.":""));
    });
  }
  private void copyProgress(InputStream in,OutputStream out,String title,long maximum)throws Exception{
    byte[] chunk=new byte[32768];long total=0,nextUpdate=0;int n;
    while((n=in.read(chunk))!=-1){
      if(Thread.currentThread().isInterrupted())throw new IOException("Download cancelado.");
      out.write(chunk,0,n);total+=n;
      if(total>maximum)throw new IOException("Download ultrapassou o limite de segurança ("+formatBytes(maximum)+").");
      if(total>=nextUpdate){busyTick(title+" — "+formatBytes(total),0,0);nextUpdate=total+262144;}
    }
    busyTick(title+" concluído: "+formatBytes(total),0,0);
  }
  private void openDownloadedApk(Uri apkUri){
    try{
      if(Build.VERSION.SDK_INT>=26 && !getPackageManager().canRequestPackageInstalls()){
        new AlertDialog.Builder(this).setTitle("Permissão para instalar APK")
          .setMessage("O Android exige autorização para instalar aplicativos pelo GitHub Manager. " +
            "Autorize 'Instalar apps desconhecidos' e depois abra o APK salvo usando o gerenciador de arquivos.")
          .setNegativeButton("Fechar",null)
          .setPositiveButton("Abrir configuração",(d,w)->{
            try{startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
              Uri.parse("package:"+getPackageName())));}catch(Exception e){fail(e);}
          }).show();return;
      }
      Intent i=new Intent(Intent.ACTION_VIEW);
      i.setDataAndType(apkUri,"application/vnd.android.package-archive");
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(i);
    }catch(Exception e){message("O Android não conseguiu abrir o instalador: "+e.getMessage()+
      ". Abra manualmente o APK na pasta onde foi salvo.");}
  }
  private void saveRemoteFolder(DownloadJob job,Uri treeUri){
    runBusy("Baixar arquivos para pasta",0,()->{
      String treeId=DocumentsContract.getTreeDocumentId(treeUri);
      Uri rootDoc=DocumentsContract.buildDocumentUriUsingTree(treeUri,treeId);
      String prefix=filenameForSaving(job.repo+(job.kind==2?"-completo-":"-selecionados-")+
        new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date()),"");
      Uri parent=DocumentsContract.createDocument(getContentResolver(),rootDoc,
        DocumentsContract.Document.MIME_TYPE_DIR,prefix);
      if(parent==null)throw new IOException("Não foi possível criar a pasta "+prefix);
      HashMap<String,Uri> directories=new HashMap<>();directories.put("",parent);
      if(job.kind==2){
        try(RemoteStream rs=openRemoteStream(requestBase(job)+"/zipball/"+segments(job.branch));
            ZipInputStream z=new ZipInputStream(rs.input)){
          ZipEntry entry;int num=0;long total=0;
          while((entry=z.getNextEntry())!=null){
            String raw=entry.getName().replace('\\','/');
            int slash=raw.indexOf('/');if(slash<0){z.closeEntry();continue;}
            String relative=raw.substring(slash+1);
            if(relative.isEmpty()){z.closeEntry();continue;}
            if(entry.isDirectory()){z.closeEntry();continue;}
            relative=checkedPath(relative);
            Uri target=createExportFile(treeUri,parent,directories,relative);
            try(OutputStream o=getContentResolver().openOutputStream(target,"w")){
              if(o==null)throw new IOException("Destino indisponível: "+relative);
              byte[] buffer=new byte[16384];int n;
              while((n=z.read(buffer))!=-1){
                o.write(buffer,0,n);total+=n;
                if(total>MAX_REPO_EXTRACT_BYTES)throw new IOException("Limite de tamanho extraído excedido.");
              }
            }
            z.closeEntry();num++;
            if(num>MAX_REPO_EXTRACT_FILES)throw new IOException("Limite de "+MAX_REPO_EXTRACT_FILES+" arquivos atingido.");
            if(num%5==0)busyTick("Extraídos "+num+" arquivos • "+formatBytes(total),0,0);
          }
          if(num==0)throw new IOException("O repositório não contém arquivos.");
          busyTick("Extraídos "+num+" arquivos • "+formatBytes(total),0,0);
        }
      }else if(job.kind==4){
        ArrayList<Entry> files=exportFiles(job);
        long total=0;int processed=0;
        for(Entry file:files){
          byte[] data=readBlob(job,file.sha);total+=data.length;
          if(total>MAX_EXPORT_BYTES)throw new IOException("Limite de tamanho excedido.");
          String relative=exportRelative(job,file.path);
          Uri target=createExportFile(treeUri,parent,directories,relative);
          try(OutputStream o=getContentResolver().openOutputStream(target,"w")){
            if(o==null)throw new IOException("Não consegui criar "+relative);
            o.write(data);
          }
          busyTick("Arquivo: "+relative,++processed,files.size());
        }
      }else throw new IOException("Exportação de pasta desconhecida.");
      return prefix;
    },folderName->message("✅ Download concluído!\nPasta criada: "+folderName+
      "\nOs arquivos foram salvos mantendo a estrutura de pastas, sem ZIP."));
  }
  private Uri createExportFile(Uri tree,Uri base,Map<String,Uri> dirs,String relative)throws Exception{
    String[] parts=checkedPath(relative).split("/");String acc="";
    Uri parent=base;
    for(int i=0;i<parts.length-1;i++){
      acc=acc.isEmpty()?parts[i]:acc+"/"+parts[i];
      if(dirs.containsKey(acc)){parent=dirs.get(acc);continue;}
      Uri made=DocumentsContract.createDocument(getContentResolver(),parent,
        DocumentsContract.Document.MIME_TYPE_DIR,parts[i]);
      if(made==null)throw new IOException("Não consegui criar subpasta "+acc);
      dirs.put(acc,made);parent=made;
    }
    Uri file=DocumentsContract.createDocument(getContentResolver(),parent,
      "application/octet-stream",parts[parts.length-1]);
    if(file==null)throw new IOException("Não consegui criar arquivo "+relative);
    return file;
  }

  private void openOutside(String url){
    startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));
  }
  private void inAppPage(String url,String title){
    if(!url.startsWith("https://github.com/")){message("Este visualizador abre somente páginas do GitHub.");return;}
    if(activeWebView!=null){activeWebView.destroy();activeWebView=null;}
    LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setBackgroundColor(0xff0d1628);
    TextView head=new TextView(this);head.setText("🌐 "+title);head.setTextColor(Color.WHITE);head.setTextSize(21);head.setPadding(16,20,16,15);layout.addView(head);
    LinearLayout controls=new LinearLayout(this);controls.setOrientation(0);layout.addView(controls);
    Button back=new Button(this);back.setAllCaps(false);back.setText("← Voltar");controls.addView(back,new LinearLayout.LayoutParams(0,-2,1));
    back.setOnClickListener(v->repoOptions());
    Button external=new Button(this);external.setAllCaps(false);external.setText("🌍 Navegador");controls.addView(external,new LinearLayout.LayoutParams(0,-2,1));
    external.setOnClickListener(v->openOutside(url));
    if(url.contains("/actions")){
      Button download=new Button(this);download.setAllCaps(false);download.setText("⬇ APK");
      controls.addView(download,new LinearLayout.LayoutParams(0,-2,0.75f));
      download.setOnClickListener(v->latestApk());
    }
    activeWebView=new WebView(this);
    WebSettings settings=activeWebView.getSettings();settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(activeWebView,false);
    activeWebView.setWebViewClient(new WebViewClient());activeWebView.setWebChromeClient(new WebChromeClient());
    layout.addView(activeWebView,new LinearLayout.LayoutParams(-1,0,1));
    layout.setOnApplyWindowInsetsListener((v,insets)->{
      v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
      return insets;
    });
    setContentView(layout);activeWebView.loadUrl(url);
  }
  @Override public void onBackPressed(){
    if(activeWebView!=null){if(activeWebView.canGoBack())activeWebView.goBack();else repoOptions();}
    else super.onBackPressed();
  }
  @Override protected void onDestroy(){
    executor.shutdownNow();if(activeWebView!=null){activeWebView.destroy();activeWebView=null;}
    super.onDestroy();
  }
}
