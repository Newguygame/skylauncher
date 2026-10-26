//go:build windows

// SkyLauncher LOCAL installer. Entire application jar is embedded. No HTTP or GitHub downloads.
// Requires a locally installed Java 17+ runtime; the jpackage Windows build bundles one.
package main

import (
 "archive/zip"
 "bytes"
 "embed"
 "encoding/base64"
 "errors"
 "fmt"
 "os"
 "os/exec"
 "path/filepath"
 "regexp"
 "strconv"
 "strings"
 "syscall"
 "unicode/utf16"
 "unsafe"
)

//go:embed bundled/SkyLauncher.jar
var application embed.FS

var user32 = syscall.NewLazyDLL("user32.dll")
var messageBox = user32.NewProc("MessageBoxW")
func dialog(title,body string,kind uintptr) bool {
 t,_:=syscall.UTF16PtrFromString(title)
 b,_:=syscall.UTF16PtrFromString(body)
 v,_,_:=messageBox.Call(0,uintptr(unsafe.Pointer(b)),uintptr(unsafe.Pointer(t)),kind)
 return v==6
}
func info(message string){dialog("SkyLauncher - Local Setup",message,0x40)}
func ask(message string)bool{return dialog("SkyLauncher - Local Setup",message,0x24)}
func fail(err error){fmt.Fprintln(os.Stderr,err);dialog("SkyLauncher - Setup Error",err.Error(),0x10)}
func jarBytes()([]byte,error){return application.ReadFile("bundled/SkyLauncher.jar")}
func validateJar(data []byte) error {
 r,e:=zip.NewReader(bytes.NewReader(data),int64(len(data)));if e!=nil{return e}
 cl,manifest:=false,false
 for _,f:=range r.File {if f.Name=="SkyLauncher.class"{cl=true};if f.Name=="META-INF/MANIFEST.MF"{manifest=true}}
 if !cl||!manifest{return errors.New("bundled launcher jar is missing classes or manifest")}
 return nil
}
func validJava(java string)bool{
 output,e:=exec.Command(java,"-version").CombinedOutput();if e!=nil{return false}
 m:=regexp.MustCompile(`(?i)version\s+"(\d+)`).FindStringSubmatch(string(output))
 if len(m)<2{return false}
 version,_:=strconv.Atoi(m[1]);return version>=17
}
func localJava()string{
 var candidates []string
 if home:=os.Getenv("JAVA_HOME");home!=""{candidates=append(candidates,filepath.Join(home,"bin","java.exe"))}
 if p,e:=exec.LookPath("java.exe");e==nil{candidates=append(candidates,p)}
 if pf:=os.Getenv("ProgramFiles");pf!=""{
  for _,dir:=range []string{filepath.Join(pf,"Java"),filepath.Join(pf,"Eclipse Adoptium"),filepath.Join(pf,"Microsoft")} {
   entries,e:=os.ReadDir(dir);if e==nil{for _,entry:=range entries{if entry.IsDir(){candidates=append(candidates,filepath.Join(dir,entry.Name(),"bin","java.exe"))}}}
  }
 }
 for _,j:=range candidates{if validJava(j){jw:=filepath.Join(filepath.Dir(j),"javaw.exe");if _,e:=os.Stat(jw);e==nil{return jw}}}
 return ""
}
func safeWrite(path string,data []byte)error{
 if e:=os.MkdirAll(filepath.Dir(path),0700);e!=nil{return e}
 tmp,e:=os.CreateTemp(filepath.Dir(path),"skylauncher-*.part");if e!=nil{return e}
 defer os.Remove(tmp.Name())
 if _,e=tmp.Write(data);e!=nil{tmp.Close();return e}
 if e=tmp.Close();e!=nil{return e}
 backup:=filepath.Join(filepath.Dir(path),"SkyLauncher.previous.jar")
 if _,e=os.Stat(path);e==nil{
  _=os.Remove(backup)
  if e=os.Rename(path,backup);e!=nil{return fmt.Errorf("close SkyLauncher before installing: %w",e)}
 }
 if e=os.Rename(tmp.Name(),path);e!=nil{_ =os.Rename(backup,path);return e}
 return nil
}
func psQuote(s string)string{return "'"+strings.ReplaceAll(s,"'","''")+"'"}
func shortcut(desktop,java,jar string)error{
 path:=filepath.Join(desktop,"SkyLauncher.lnk")
 cmd:="$w=New-Object -ComObject WScript.Shell;"+
  "$s=$w.CreateShortcut("+psQuote(path)+");"+
  "$s.TargetPath="+psQuote(java)+";"+
  "$s.Arguments="+psQuote("-jar \""+jar+"\"")+";"+
  "$s.WorkingDirectory="+psQuote(filepath.Dir(jar))+";"+
  "$s.Description='SkyLauncher';$s.Save()"
 units:=utf16.Encode([]rune(cmd));raw:=make([]byte,0,len(units)*2)
 for _,v:=range units{raw=append(raw,byte(v),byte(v>>8))}
 encoded:=base64.StdEncoding.EncodeToString(raw)
 out,e:=exec.Command("powershell.exe","-NoProfile","-NonInteractive","-EncodedCommand",encoded).CombinedOutput()
 if e!=nil{return fmt.Errorf("could not make shortcut: %w: %s",e,string(out))}
 return nil
}
func desktopPath() string {
 for _,p:=range []string{filepath.Join(os.Getenv("OneDrive"),"Desktop"),filepath.Join(os.Getenv("USERPROFILE"),"Desktop")} {
  if p=="Desktop"{continue}
  if st,e:=os.Stat(p);e==nil&&st.IsDir(){return p}
 }
 return ""
}
func install()error{
 if !ask("Install SkyLauncher using the application bundled inside this EXE?\n\nThis local installer does NOT download any launcher files from GitHub or the internet.\n\nIt requires Java 17+ already installed. For a self-contained installer with bundled Java, build the native Windows EXE using GitHub Actions or the included Windows build script."){return nil}
 java:=localJava()
 if java==""{return errors.New("Java 17+ was not found. This lightweight offline installer never downloads Java. Install Java 17+ yourself, or use the full Windows installer built by the included GitHub workflow (bundles Java).")}
 data,e:=jarBytes();if e!=nil{return e}
 if e=validateJar(data);e!=nil{return e}
 home,e:=os.UserHomeDir();if e!=nil{return e}
 app:=filepath.Join(home,".ghmodlauncher","launcher-app")
 dest:=filepath.Join(app,"SkyLauncher.jar")
 if e=safeWrite(dest,data);e!=nil{return e}
 if e=os.WriteFile(filepath.Join(app,"release-tag.txt"),[]byte("local-bundled\n"),0600);e!=nil{return e}
 if desktop:=desktopPath();desktop!=""&&ask("SkyLauncher is installed. Create a desktop shortcut?") {
  if e=shortcut(desktop,java,dest);e!=nil{info("Installed, but the shortcut could not be created: "+e.Error())}
 }
 if ask("SkyLauncher installed successfully!\n\nLaunch it now?") {
  if e=exec.Command(java,"-jar",dest).Start();e!=nil{return e}
 }
 return nil
}
func main(){
 for _,arg:=range os.Args[1:]{if arg=="--selftest"{
  data,e:=jarBytes();if e==nil{e=validateJar(data)}
  if e!=nil{fail(e);os.Exit(1)};fmt.Println("Bundled jar OK; local-only installer (no network calls)");return
 }}
 if e:=install();e!=nil{fail(e);os.Exit(1)}
}
