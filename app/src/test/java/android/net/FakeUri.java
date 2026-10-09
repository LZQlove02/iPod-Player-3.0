package android.net;

import android.os.Parcel;

import java.util.Collections;
import java.util.List;

/**
 * 单元测试用的 Uri 替身。
 *
 * 背景：AGP 的 mockable android.jar 把所有方法都换成「返回默认值」的桩，
 * 于是 `Uri.parse()` 在 JVM 单测里恒为 null，而 {@code Song.uri} 是非空类型。
 * 曲库聚合逻辑（LibraryAggregator）根本不读 uri，只需要一个非空实例。
 *
 * 放在 `android.net` 包里是因为 {@code Uri()} 构造器是包可见的；
 * 用 Unsafe.allocateInstance 不行 —— Uri 是抽象类，会抛 InstantiationException。
 */
public class FakeUri extends Uri {

    public FakeUri() {
    }

    @Override
    public boolean isHierarchical() {
        return false;
    }

    @Override
    public boolean isRelative() {
        return true;
    }

    @Override
    public String getScheme() {
        return "fake";
    }

    @Override
    public String getSchemeSpecificPart() {
        return "";
    }

    @Override
    public String getEncodedSchemeSpecificPart() {
        return "";
    }

    @Override
    public String getAuthority() {
        return null;
    }

    @Override
    public String getEncodedAuthority() {
        return null;
    }

    @Override
    public String getUserInfo() {
        return null;
    }

    @Override
    public String getEncodedUserInfo() {
        return null;
    }

    @Override
    public String getHost() {
        return null;
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public String getPath() {
        return "";
    }

    @Override
    public String getEncodedPath() {
        return "";
    }

    @Override
    public String getQuery() {
        return null;
    }

    @Override
    public String getEncodedQuery() {
        return null;
    }

    @Override
    public String getFragment() {
        return null;
    }

    @Override
    public String getEncodedFragment() {
        return null;
    }

    @Override
    public List<String> getPathSegments() {
        return Collections.emptyList();
    }

    @Override
    public String getLastPathSegment() {
        return null;
    }

    @Override
    public Uri.Builder buildUpon() {
        return new Uri.Builder();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // 测试替身：永远不会真的被序列化
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "fake://";
    }
}
