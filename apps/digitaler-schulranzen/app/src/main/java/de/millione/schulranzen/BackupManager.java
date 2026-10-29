package de.millione.schulranzen;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Complete V15 snapshot: app state, notebooks, annotations, clips, scans and imported book PDFs. */
public final class BackupManager {
    public interface Callback { void done(boolean ok,String message); }
    private static final int BACKUP_FORMAT=15;
    private static final int LOCAL_HISTORY=3;
    private final Context c;
    private final SharedPreferences bag;
    public BackupManager(Context context){c=context.getApplicationContext();bag=c.getSharedPreferences("bag",0);}

    /** Creates an integrity-checked snapshot. Existing content:// books are first copied into app-owned storage. */
    public synchronized File createLocalBackup() throws Exception {
        localizeAllBooksOrThrow();
        File dir=new File(c.getFilesDir(),"backups");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Backup-Ordner konnte nicht erstellt werden");
        File out=new File(dir,"local-latest.zip"),tmp=new File(dir,"local-latest.tmp");
        ArrayList<EntryInfo> entries=new ArrayList<>();
        collectFiles(c.getFilesDir(),"files/",dir,entries);
        File prefsDir=new File(c.getApplicationInfo().dataDir,"shared_prefs");if(prefsDir.exists())collectFiles(prefsDir,"shared_prefs/",null,entries);
        JSONObject manifest=manifest(entries);
        try(ZipOutputStream z=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))){
            byte[] buf=new byte[64*1024];
            for(EntryInfo info:entries){ZipEntry e=new ZipEntry(info.path);e.setTime(info.file.lastModified());z.putNextEntry(e);try(FileInputStream in=new FileInputStream(info.file)){int n;while((n=in.read(buf))>0)z.write(buf,0,n);}z.closeEntry();}
            ZipEntry me=new ZipEntry("backup-manifest.json");z.putNextEntry(me);z.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));z.closeEntry();
        }
        if(out.exists()&&!out.delete())throw new IllegalStateException("Altes Backup konnte nicht ersetzt werden");
        if(!tmp.renameTo(out)){copy(tmp,out);tmp.delete();}
        keepHistory(out,dir);
        return out;
    }

    private void localizeAllBooksOrThrow() throws Exception {
        LibraryStore library=new LibraryStore(c);ArrayList<LibraryStore.Item> changed=new ArrayList<>();
        for(LibraryStore.Item item:library.all(true)){
            if(!LibraryStore.TYPE_BOOK.equals(item.type)||item.uri==null||item.uri.isEmpty())continue;
            File local=AppFileResolver.bookFile(c,item.id);
            if(local.exists()&&local.length()>0){String token=AppFileResolver.bookToken(item.id);if(!token.equals(item.uri)){item.uri=token;changed.add(item);}continue;}
            if(AppFileResolver.isAppFile(item.uri))throw new IllegalStateException("Buchdatei fehlt: "+item.title);
            try{item.uri=AppFileResolver.copyBook(c, Uri.parse(item.uri),item.id);changed.add(item);}catch(Exception ex){throw new IllegalStateException("Buch konnte nicht vollständig gesichert werden: "+item.title,ex);}
        }
        for(LibraryStore.Item i:changed)library.update(i);
    }

    private static void collectFiles(File dir,String prefix,File skipDir,ArrayList<EntryInfo> out)throws Exception{
        File[] fs=dir.listFiles();if(fs==null)return;
        for(File f:fs){if(skipDir!=null&&sameFile(f,skipDir))continue;if(f.isDirectory()){collectFiles(f,prefix+f.getName()+"/",skipDir,out);continue;}EntryInfo e=new EntryInfo();e.file=f;e.path=prefix+f.getName();e.size=f.length();e.sha256=sha256(f);out.add(e);}
    }

    private static JSONObject manifest(ArrayList<EntryInfo> entries)throws Exception{
        JSONObject root=new JSONObject();root.put("format",BACKUP_FORMAT);root.put("created",System.currentTimeMillis());root.put("app","Digitaler Schulranzen");JSONArray a=new JSONArray();
        for(EntryInfo e:entries){JSONObject o=new JSONObject();o.put("path",e.path);o.put("size",e.size);o.put("sha256",e.sha256);a.put(o);}root.put("entries",a);return root;
    }

    private static String sha256(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] b=new byte[64*1024];int n;while((n=in.read(b))>0)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:md.digest())s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}

    private void keepHistory(File latest,File dir)throws Exception{
        String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date());File snap=new File(dir,"snapshot-"+stamp+".zip");copy(latest,snap);
        File[] list=dir.listFiles((d,n)->n.startsWith("snapshot-")&&n.endsWith(".zip"));if(list==null||list.length<=LOCAL_HISTORY)return;
        java.util.Arrays.sort(list,(a,b)->Long.compare(b.lastModified(),a.lastModified()));for(int i=LOCAL_HISTORY;i<list.length;i++)list[i].delete();
    }

    public void uploadAsync(Callback cb){new Thread(()->{try{File f=createLocalBackup();String token=bag.getString("token","");String server=normalize(bag.getString("server",""));if(token.isEmpty()||server.isEmpty())throw new IllegalStateException("Nicht am Server angemeldet");String boundary="----Schulranzen"+System.currentTimeMillis();HttpURLConnection h=(HttpURLConnection)new URL(server+"/api/sync/upload").openConnection();h.setConnectTimeout(6000);h.setReadTimeout(120000);h.setRequestMethod("POST");h.setDoOutput(true);h.setRequestProperty("Authorization","Bearer "+token);h.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);try(OutputStream out=new BufferedOutputStream(h.getOutputStream());FileInputStream in=new FileInputStream(f)){String head="--"+boundary+"\r\nContent-Disposition: form-data; name=\"backup\"; filename=\"schulranzen-v15-backup.zip\"\r\nContent-Type: application/zip\r\n\r\n";out.write(head.getBytes(StandardCharsets.UTF_8));byte[] buf=new byte[64*1024];int n;while((n=in.read(buf))>0)out.write(buf,0,n);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));}int code=h.getResponseCode();String response=read(code>=200&&code<300?h.getInputStream():h.getErrorStream());h.disconnect();if(code<200||code>=300)throw new Exception("Serverfehler "+code+" "+response);post(cb,true,"Vollständiges Backup inklusive Bücher gespeichert");}catch(Exception e){post(cb,false,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}}).start();}

    public void restoreLatestAsync(Callback cb){new Thread(()->{try{String token=bag.getString("token","");String server=normalize(bag.getString("server",""));if(token.isEmpty()||server.isEmpty())throw new IllegalStateException("Nicht am Server angemeldet");HttpURLConnection h=(HttpURLConnection)new URL(server+"/api/sync/latest").openConnection();h.setConnectTimeout(6000);h.setReadTimeout(120000);h.setRequestProperty("Authorization","Bearer "+token);int code=h.getResponseCode();if(code<200||code>=300)throw new Exception("Serverfehler "+code);File dl=new File(c.getCacheDir(),"restore-v15.zip");try(InputStream in=h.getInputStream();FileOutputStream out=new FileOutputStream(dl)){byte[] b=new byte[64*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}h.disconnect();boolean verified=restoreZip(dl);new LibraryStore(c).relinkLocalBooks();post(cb,true,verified?"Backup geprüft und vollständig wiederhergestellt – App neu öffnen":"Älteres Backup wiederhergestellt – App neu öffnen");}catch(Exception e){post(cb,false,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}}).start();}

    /** Restores via a staging directory, validates all V15 checksums, then replaces the live data. */
    private boolean restoreZip(File zip)throws Exception{
        File stage=new File(c.getCacheDir(),"restore-stage");deleteTree(stage);stage.mkdirs();Set<String> seen=new HashSet<>();
        try(ZipInputStream z=new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))){ZipEntry e;byte[] buf=new byte[64*1024];while((e=z.getNextEntry())!=null){String name=e.getName();if(name.contains("..")||name.startsWith("/")||(!name.startsWith("files/")&&!name.startsWith("shared_prefs/")&&!name.equals("backup-manifest.json")))continue;File dest=new File(stage,name);String root=stage.getCanonicalPath()+File.separator;if(!dest.getCanonicalPath().startsWith(root))continue;if(e.isDirectory()){dest.mkdirs();continue;}File parent=dest.getParentFile();if(parent!=null)parent.mkdirs();try(FileOutputStream out=new FileOutputStream(dest)){int n;while((n=z.read(buf))>0)out.write(buf,0,n);}seen.add(name);}}
        File mf=new File(stage,"backup-manifest.json");boolean verified=mf.exists();if(verified)verifyManifest(stage,mf,seen);
        File liveFiles=c.getFilesDir(),backupDir=new File(liveFiles,"backups");File preserve=new File(c.getCacheDir(),"backup-preserve");deleteTree(preserve);if(backupDir.exists())copyTree(backupDir,preserve);
        File stagedFiles=new File(stage,"files");deleteChildren(liveFiles);if(stagedFiles.exists())copyChildren(stagedFiles,liveFiles);if(preserve.exists())copyTree(preserve,backupDir);deleteTree(preserve);
        File prefsDir=new File(c.getApplicationInfo().dataDir,"shared_prefs"),stagedPrefs=new File(stage,"shared_prefs");if(stagedPrefs.exists()){deleteTree(prefsDir);copyTree(stagedPrefs,prefsDir);}
        deleteTree(stage);return verified;
    }

    private static void verifyManifest(File stage,File manifest,Set<String> seen)throws Exception{
        String json=read(new FileInputStream(manifest));JSONObject root=new JSONObject(json);if(root.optInt("format",0)<14)throw new IllegalStateException("Unbekanntes Backup-Format");JSONArray a=root.optJSONArray("entries");if(a==null)throw new IllegalStateException("Backup-Manifest fehlt");
        for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);String path=o.getString("path");if(!seen.contains(path))throw new IllegalStateException("Backup unvollständig: "+path);File f=new File(stage,path);if(!f.exists()||f.length()!=o.optLong("size",-1)||!sha256(f).equalsIgnoreCase(o.optString("sha256","")))throw new IllegalStateException("Backup-Prüfsumme fehlerhaft: "+path);}
    }

    private static void deleteChildren(File dir){File[] fs=dir.listFiles();if(fs!=null)for(File f:fs)deleteTree(f);}
    private static void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] fs=f.listFiles();if(fs!=null)for(File x:fs)deleteTree(x);}f.delete();}
    private static void copyChildren(File src,File dst)throws Exception{File[] fs=src.listFiles();if(fs!=null)for(File f:fs)copyTree(f,new File(dst,f.getName()));}
    private static void copyTree(File src,File dst)throws Exception{if(src.isDirectory()){if(!dst.exists())dst.mkdirs();File[] fs=src.listFiles();if(fs!=null)for(File f:fs)copyTree(f,new File(dst,f.getName()));}else copy(src,dst);}
    private void post(Callback cb,boolean ok,String msg){new android.os.Handler(c.getMainLooper()).post(()->cb.done(ok,msg));}
    private static String normalize(String s){if(s==null)return"";s=s.trim();if(s.isEmpty())return"";if(!s.startsWith("http://")&&!s.startsWith("https://"))s="http://"+s;while(s.endsWith("/"))s=s.substring(0,s.length()-1);return s;}
    private static String read(InputStream in)throws Exception{if(in==null)return"";try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=x.read(b))>0)o.write(b,0,n);return o.toString("UTF-8");}}
    private static void copy(File a,File b)throws Exception{File parent=b.getParentFile();if(parent!=null&&!parent.exists())parent.mkdirs();try(FileInputStream in=new FileInputStream(a);FileOutputStream out=new FileOutputStream(b)){byte[] x=new byte[64*1024];int n;while((n=in.read(x))>0)out.write(x,0,n);}}
    private static boolean sameFile(File a,File b){try{return a.getCanonicalFile().equals(b.getCanonicalFile());}catch(Exception e){return a.equals(b);}}
    private static final class EntryInfo{File file;String path,sha256;long size;}
}
