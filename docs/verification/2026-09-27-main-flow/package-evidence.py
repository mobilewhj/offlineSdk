from pathlib import Path
import hashlib,json,subprocess,shutil,xml.etree.ElementTree as E,zipfile,difflib
root=Path.cwd();src=root/'build/reports/main-flow-refactor';out=root/'docs/verification/2026-09-27-main-flow';out.mkdir(parents=True,exist_ok=True)
baseline=root/'build/reports/main-flow-baseline'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def counts(folder):
 rs=[E.parse(p).getroot() for p in folder.glob('TEST-*.xml')]
 assert rs,folder
 return {k:sum(int(r.get(k,0)) for r in rs) for k in ['tests','failures','errors','skipped']}
for name in ['full-build.log','external-regressions.log','local-candidate.log','aar-consumer.log']:
 assert 'BUILD SUCCESSFUL' in (src/name).read_text(),name
for p in src.iterdir():
 if p.is_file() and p.suffix in ('.log','.txt','.json','.diff','.py'):shutil.copy2(p,out/p.name)
for group in ['offlineSdk-repository-tests','app-repository-tests','offlineSdk-external-tests']:
 shutil.copytree(src/group,out/group,dirs_exist_ok=True)
dest=out/'app-aar-tests';dest.mkdir(exist_ok=True)
for p in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):shutil.copy2(p,dest/p.name)
repo=root/'build/repo/com/github/mobilewhj/offlineSdk/offlineSdk/0.3.0'
artifacts={p.name:sha(p) for p in sorted(repo.iterdir()) if p.suffix in ['.aar','.jar','.pom','.module']}
assert 'Consumed SDK AAR SHA-256: '+artifacts['offlineSdk-0.3.0.aar'] in (src/'aar-consumer.log').read_text()
checksum_count=0
for p in repo.iterdir():
 algorithm=p.suffix[1:]
 if algorithm in ('md5','sha1','sha256','sha512'):
  assert hashlib.new(algorithm,p.with_suffix('').read_bytes()).hexdigest()==p.read_text().strip(),p
  checksum_count+=1
api=json.loads((src/'api-compatibility.json').read_text());assert api['status']=='passed'
assert api['artifacts']['after']['aar']['sha256']==artifacts['offlineSdk-0.3.0.aar']
files=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z']).decode().split('\0')
selected=sorted({f for f in files if f and (root/f).is_file() and not f.startswith('docs/') and f not in ['README.md','README.en.md','CHANGELOG.md']})
(out/'source-sha256.txt').write_text(''.join(sha(root/f)+'  '+f+'\n' for f in selected))
old=json.loads((baseline/'source-manifest.json').read_text())
changes=[f for f in selected if old.get(f)!=sha(root/f)]
assert changes==['offlineSdk/src/main/java/com/offline/tool/ManagedOfflineSdk.kt','offlineSdk/src/test/java/com/offline/tool/ManagedOfflineRegressionTest.kt'],changes
current_app={f for f in selected if f.startswith('app/')};before_app={f for f in old if f.startswith('app/')}
assert current_app==before_app
assert all(old[f]==sha(root/f) for f in current_app)
(out/'structure.diff').write_text(''.join(''.join(difflib.unified_diff((baseline/'workspace'/f).read_text().splitlines(True),(root/f).read_text().splitlines(True),fromfile='before/'+f,tofile='after/'+f)) for f in changes))
(out/'baseline-source-manifest.json').write_bytes((baseline/'source-manifest.json').read_bytes())
rerun='$TRACKING_REPO/.trellis/tasks/09-24-offline-sdk-managed-refactor/research/sdk-quality-acceptance-2026-09-27/rerun-tests.init.gradle'
probe='$TRACKING_REPO/.trellis/tasks/09-24-offline-sdk-managed-refactor/research/sdk-acceptance-2026-09-27'
full=f'./gradlew :offlineSdk:testDebugUnitTest :offlineSdk:lintRelease :offlineSdk:compileDebugAndroidTestKotlin :offlineSdk:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease -I {rerun} --no-daemon --console=plain'
external=f'./gradlew :offlineSdk:testDebugUnitTest -I {probe}/review-probes.init.gradle -PreviewProbesDir={probe} -I {rerun} --no-daemon --console=plain'
consumer=f'./gradlew :app:verifyOfflineSdkAar :app:dependencyInsight --configuration debugRuntimeClasspath --dependency offlineSdk :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug -PsdkVersion=0.3.0 -PsmokeSdkVersion=0.3.0 -I scripts/aar-consumer-smoke.init.gradle -I {rerun} --no-daemon --console=plain'
report={
 'date':'2026-09-27','scope':'Q1–Q3关闭后的ManagedOfflineSdk主流程继续优化；公开API与契约不变',
 'branch':subprocess.check_output(['git','branch','--show-current']).decode().strip(),
 'head':subprocess.check_output(['git','rev-parse','HEAD']).decode().strip(),
 'implementationCommitted':False,'pushed':False,'tagged':False,'formallyPublished':False,
 'acceptance':'保留R1–R9及Q1–Q3已通过事实；本轮优化与候选交原规划任务复审，设备及正式发布未完成，业务App继续等待',
 'environment':{'java':'OpenJDK JBR 17.0.14+1-b1367.22','gradle':'8.11.1','agp':'8.10.1','kotlin':'2.0.21','minSdk':24,'compileSdk':35},
 'testResults':{g:counts(out/g) for g in ['offlineSdk-repository-tests','app-repository-tests','offlineSdk-external-tests','app-aar-tests']},
 'sourceManifestFileCount':len(selected),'sourceManifestSha256':sha(out/'source-sha256.txt'),
 'sourceJarMatchesCurrentProductionSources':api['checks']['current_sources_match_workspace'],
 'productionSourceCount':api['current_sources_vs_workspace']['matches'],
 'changedSourceAndBuildFilesSinceRoundStart':changes,'demoFilesChangedSinceRoundStart':[],
 'demoFilesCompared':len(current_app),'publicManagerSignaturesEqual':api['checks']['manager_public_jvm_signatures_equal'],
 'apiComparisonScope':'管理器构造器、state、7方法及默认参数入口的javap声明和descriptor；过滤access$内部桥接；并非完整ABI工具验证',
 'baselineDirectory':str(baseline),'baselineSourceManifestSha256':sha(baseline/'source-manifest.json'),
 'baselineAarSha256':api['artifacts']['before']['aar']['sha256'],
 'localCoordinate':'com.github.mobilewhj.offlineSdk:offlineSdk:0.3.0','artifactDirectory':str(repo),'artifactSha256':artifacts,
 'consumerResolvedAarSha256':artifacts['offlineSdk-0.3.0.aar'],'generatedChecksumFilesVerified':checksum_count,
 'structureDiffBase':'本轮改动前完整工作区快照；其15个生产源码与已验收的159f24基线sources JAR逐字节一致。',
 'commands':[
 {'command':full,'result':'BUILD SUCCESSFUL；SDK105、Demo9；SDK/Demo lint、Release/R8、Android测试源码编译通过','log':'full-build.log'},
 {'command':external,'result':'BUILD SUCCESSFUL；SDK111=仓库105+外部6，全部通过','log':'external-regressions.log'},
 {'command':'./gradlew :offlineSdk:publishReleasePublicationToLocalReleaseRepository -PsdkVersion=0.3.0 --no-daemon --console=plain','result':'BUILD SUCCESSFUL；仅本地候选生成，不是正式发布','log':'local-candidate.log'},
 {'command':consumer,'result':'BUILD SUCCESSFUL；消费者实际解析摘要相符，Debug/Release/R8、Demo9、lint通过','log':'aar-consumer.log'},
 {'command':'python3 scripts/generate-sample.py --check','result':'通过；SHA-256=28069f115248f28f7bc5d8dc42799c2d75b9c641caa374549657042f2b3ab47b','log':'sample-check.log'},
 {'command':'git diff --check','result':'通过','log':'diff-check.log'},
 {'command':'python3 build/reports/main-flow-refactor/api-compare.py','result':'passed；固定源码及管理器公开JVM签名比较','log':'api-compatibility.json'},
 {'command':'python3 /tmp/package_mainflow_evidence.py','result':'产物/消费/源码及生成校验文件一致；Demo文件集合和摘要未变','script':'package-evidence.py'}
 ],
 'notVerified':['本轮继续优化的原规划任务复审与整体验收','设备UI及真实生命周期','设备Main/StrictMode','系统WebView/X5实际运行','真实后端成功/失败HTTP编码和上传','远端正式坐标解析/tag/实现提交','完整ABI工具验证'],
}
for group,count in report['testResults'].items():
 assert count['failures']==count['errors']==count['skipped']==0,(group,count)
assert report['testResults']['offlineSdk-repository-tests']['tests']==105
assert report['testResults']['offlineSdk-external-tests']['tests']==111
assert report['testResults']['app-repository-tests']['tests']==report['testResults']['app-aar-tests']['tests']==9
(out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
(out/'git-status.txt').write_bytes(subprocess.check_output(['git','status','--short','--branch']))
shutil.copy2('/tmp/package_mainflow_evidence.py',out/'package-evidence.py')
p=root/'docs/SDK-MANAGED-REFACTOR-DELIVERY.md';s=p.read_text().replace('MAIN_FLOW_SOURCE_MANIFEST_SHA',report['sourceManifestSha256']);p.write_text(s)
print(json.dumps({k:report[k] for k in ['testResults','artifactSha256','sourceManifestSha256','sourceManifestFileCount','generatedChecksumFilesVerified','changedSourceAndBuildFilesSinceRoundStart','demoFilesCompared']},ensure_ascii=False,indent=2))
