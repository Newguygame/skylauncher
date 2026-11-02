import importlib.util, io, json, threading, urllib.request, urllib.error, unittest
from pathlib import Path
p=Path(__file__).with_name('server.py')
spec=importlib.util.spec_from_file_location('skybridge', p)
bridge=importlib.util.module_from_spec(spec);spec.loader.exec_module(bridge)
class FakeCF:
    def search(self, query, version='', loader='', slug='', kind='mod'):
        return [{'id':101,'name':'Test mod','slug':'test-mod','summary':'','downloads':5, 'version':version,'loader':loader}]
    def get_mod(self, mid, kind='mod'):return {'id':mid,'name':'Test mod','slug':'test-mod'}
    def files(self, mid, version, loader, kind='mod'):return [{'id':202,'name':'test.jar'}]
    def file_info(self,mid,fid):return {'id':fid,'modId':mid,'fileName':'test.jar','hashes':[]}
    def download(self,mid,fid):return io.BytesIO(b'jar bytes'), {'fileName':'test.jar'}
class TestPublic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server=bridge.Server(('127.0.0.1',0),FakeCF(),'server-private-api-key',public_access=True)
        cls.thread=threading.Thread(target=cls.server.serve_forever, daemon=True);cls.thread.start()
        cls.base=f'http://127.0.0.1:{cls.server.server_port}'
    @classmethod
    def tearDownClass(cls):cls.server.shutdown();cls.server.server_close()
    def get(self,path,token=None):
        h={} if token is None else {'Authorization':'Bearer '+token}
        with urllib.request.urlopen(urllib.request.Request(self.base+path,headers=h),timeout=3) as r:return r.read()
    def test_no_token_needed(self):
        info=json.loads(self.get('/health'));self.assertTrue(info['ok'])
        mods=json.loads(self.get('/v1/search?q=test&version=1.21.1&loader=neoforge'))['data']
        self.assertEqual(mods[0]['loader'],'neoforge')
    def test_file_download(self):self.assertEqual(self.get('/v1/download/101/202'),b'jar bytes')
    def test_invalid_endpoint(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:self.get('/config.json')
        self.assertEqual(ctx.exception.code,404)
    def test_high_default_limits(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as temp:
            settings=Path(temp)/'config.json'
            settings.write_text(json.dumps({'CURSEFORGE_API_KEY':'example-api-key-1234567890',
                'SKYLAUNCHER_PUBLIC_ACCESS':True,'SKYLAUNCHER_DOWNLOADS_PER_HOUR':3000,
                'SKYLAUNCHER_REQUESTS_PER_MINUTE':300,'SKYLAUNCHER_SIMULTANEOUS_DOWNLOADS':4}))
            _,_,_,_,_,limits=bridge.load_settings(config_path=settings,environ={})
            self.assertEqual(limits,{'downloads_per_hour':3000,'requests_per_minute':300,'simultaneous_downloads':4})
    def test_config_rejects_excessive_limit(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as temp:
            settings=Path(temp)/'config.json'
            settings.write_text(json.dumps({'CURSEFORGE_API_KEY':'example-api-key-1234567890',
                'SKYLAUNCHER_DOWNLOADS_PER_HOUR':100000}))
            with self.assertRaisesRegex(ValueError,'between 1 and 10000'):
                bridge.load_settings(config_path=settings,environ={})
    def test_limits(self):
        limiter=bridge.RequestLimiter(2,60)
        self.assertTrue(limiter.allow('ip'));self.assertTrue(limiter.allow('ip'));self.assertFalse(limiter.allow('ip'))
    def test_author_opt_out(self):
        self.assertFalse(bridge.allowed_cdn('https://malicious.com/files/1'))
        self.assertFalse(bridge.allowed_cdn('http://edge.forgecdn.net/files/1'))
    def test_private_option_still_works(self):
        srv=bridge.Server(('127.0.0.1',0),FakeCF(),'privatekey',token='secret',public_access=False)
        th=threading.Thread(target=srv.serve_forever,daemon=True);th.start()
        try:
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(f'http://127.0.0.1:{srv.server_port}/v1/search?q=test')
            self.assertEqual(ctx.exception.code,401)
        finally:srv.shutdown();srv.server_close()
if __name__=='__main__':unittest.main(verbosity=2)
