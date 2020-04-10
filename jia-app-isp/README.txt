#新建虚拟环境
python3 -m venv env

#启动虚拟环境
source env/bin/activate

#停止虚拟环境
deactivate

#安装模块
pip3 install -r requirements.txt

#构建支持SSL的uWSGI
CFLAGS="-I/home/isp/apps/openssl/include" LDFLAGS="-L/home/isp/apps/openssl/lib" UWSGI_PROFILE_OVERRIDE=ssl=true pip3 install uwsgi -I --no-cache-dir

#nginx配置
    location / {
      include uwsgi_params;
      uwsgi_param SCRIPT_NAME /api;
      uwsgi_pass unix:/home/isp/webapps/hosts/console/uwsgi.sock;
    }

#启动uWSGI
uwsgi config.ini
