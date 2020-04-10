import os
import time
import configparser
import requests
import subprocess
from flask import Flask, jsonify, request, make_response, abort

app = Flask(__name__)

app.config['JSON_AS_ASCII'] = False


def jsonresp(data, **attrs):
    code = attrs['code'] if 'code' in attrs else "E0"
    msg = attrs['msg'] if 'msg' in attrs else "success"
    status = attrs['status'] if 'status' in attrs else "200"
    return jsonify({"data": data, "code": code, "msg": msg, "status": status})


def has_perms(req):
    cf = configparser.ConfigParser()
    cf.read('./config.ini', encoding='UTF8')
    if not req.args or not 'appcn' in req.args or not req.args.get("appcn") == cf.get("app", "appcn"):
        return False
    else:
        return True


@app.route('/')
def index():
    return "Hello, World!"

# 已安装服务列表
@app.route('/app/list', methods=['GET'])
def app_list():
    if not has_perms(request):
        abort(401)
    L = []
    for root, dirs, files in os.walk("/home/isp/bin"):
        for file in files:
            if os.path.splitext(file)[1] == '.sh':
                pkg = os.path.splitext(file)[0]
                ret = subprocess.check_output(
                    "/bin/sh /home/isp/bin/%s.sh status" % (pkg), shell=True)
                L.append({"name": pkg, "status": ret.decode()})
    return jsonresp(L)

# 所有服务列表
@app.route('/pkg/list', methods=['GET'])
def pkg_list():
    if not has_perms(request):
        abort(401)
    L = []
    for root, dirs, files in os.walk("/home/isp/bin"):
        for file in files:
            if os.path.splitext(file)[1] == '.sh':
                L.append(os.path.splitext(file)[0])
    rets = []
    pkgs = requests.get(
        "http://install.chcbz.net/shell/sh_list.txt").text.split()
    for pkg in pkgs:
        if pkg in L:
            ret = subprocess.check_output(
                "/bin/sh /home/isp/bin/%s.sh status" % (pkg), shell=True)
            rets.append({"name": pkg, "status": ret.decode()})
        else:
            rets.append({"name": pkg, "status": "-1"})
    return jsonresp(rets)

# 检查服务是否可安装
@app.route('/app/check', methods=['GET'])
def app_check():
    if not has_perms(request):
        abort(401)
    if not request.args or not 'name' in request.args:
        abort(400)
    app_name = request.args.get("name")
    if os.path.exists("/home/isp/bin/%s.sh" % (app_name)):
        return jsonresp(None, code="E999", msg="已安装该服务")
    return jsonresp(None)

# 安装服务
@app.route('/app/install', methods=['GET'])
def app_install():
    if not has_perms(request):
        abort(401)
    if not request.args or not 'name' in request.args:
        abort(400)
    app_name = request.args.get("name")
    if os.path.exists("/home/isp/bin/%s.sh" % (app_name)):
        return jsonresp(None, code="E999", msg="已安装该服务")
    subprocess.Popen(
        "curl -s -S -L http://install.chcbz.net/shell/%s_install.sh | /bin/sh" % (app_name), shell=True)
    return jsonresp(None)

# 启动服务
@app.route('/app/start', methods=['GET'])
def app_start():
    if not has_perms(request):
        abort(401)
    if not request.args or not 'name' in request.args:
        abort(400)
    app_name = request.args.get("name")
    if not os.path.exists("/home/isp/bin/%s.sh" % (app_name)):
        return jsonresp(None, code="E999", msg="无法启动，请先安装该服务")
    ret = subprocess.check_output(
        "/bin/sh /home/isp/bin/%s.sh status" % (app_name), shell=True)
    if ret.decode() == "1":
        return jsonresp(None, code="E999", msg="该服务正在运行中")
    subprocess.Popen(
        "/bin/sh /home/isp/bin/%s.sh start" % (app_name), shell=True)
    return jsonresp(None)

# 停止服务
@app.route('/app/stop', methods=['GET'])
def app_stop():
    if not has_perms(request):
        abort(401)
    if not request.args or not 'name' in request.args:
        abort(400)
    app_name = request.args.get("name")
    if not os.path.exists("/home/isp/bin/%s.sh" % (app_name)):
        return jsonresp(None, code="E999", msg="无法启动，请先安装该服务")
    ret = subprocess.check_output(
        "/bin/sh /home/isp/bin/%s.sh status" % (app_name), shell=True)
    if ret.decode() == "0":
        return jsonresp(None, code="E999", msg="该服务已经停止运行")
    subprocess.Popen(
        "/bin/sh /home/isp/bin/%s.sh stop" % (app_name), shell=True)
    return jsonresp(None)

# 重启服务
@app.route('/app/restart', methods=['GET'])
def app_restart():
    if not has_perms(request):
        abort(401)
    if not request.args or not 'name' in request.args:
        abort(400)
    app_name = request.args.get("name")
    if not os.path.exists("/home/isp/bin/%s.sh" % (app_name)):
        return jsonresp(None, code="E999", msg="无法启动，请先安装该服务")
    subprocess.Popen(
        "/bin/sh /home/isp/bin/%s.sh restart" % (app_name), shell=True)
    return jsonresp(None)

# 查看服务状态
@app.route('/app/status', methods=['GET'])
def app_status():
    if not has_perms(request):
        abort(401)
    if not request.args or not 'name' in request.args:
        abort(400)
    app_name = request.args.get("name")
    if not os.path.exists("/home/isp/bin/%s.sh" % (app_name)):
        return jsonresp(None, code="E999", msg="该服务还没安装")
    ret = subprocess.check_output(
        "/bin/sh /home/isp/bin/%s.sh status" % (app_name), shell=True)
    return jsonresp(ret.decode())


@app.errorhandler(404)
def not_found(error):
    return make_response(jsonify({'error': 'Not found'}), 404)


if __name__ == '__main__':
    app.run(debug=True)
