package com.todobom.opennotescanner;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;
import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.todobom.opennotescanner.helpers.Utils;

import java.io.File;

public class FullScreenViewActivity extends AppCompatActivity {

    private Utils utils;
    private FullScreenImageAdapter mAdapter;
    private ViewPager mViewPager;
    private AlertDialog.Builder deleteConfirmBuilder;
    private ImageLoader mImageLoader;
    private ImageSize mTargetSize;
    private int mMaxTexture;
    private Toolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_fullscreen_view);

        toolbar = (Toolbar) findViewById(R.id.FullImageViewToolbar);
        setUpToolbar();

        mViewPager = (ViewPager) findViewById(R.id.pager);

        utils = new Utils(getApplicationContext());

        Intent i = getIntent();
        int position = i.getIntExtra("position", 0);

        // initialize Universal Image Loader
        ImageLoaderConfiguration config = new ImageLoaderConfiguration.Builder(this).build();
        mImageLoader = ImageLoader.getInstance();
        mImageLoader.init(config);

        mMaxTexture = Utils.getMaxTextureSize();
        Log.d("FullScreenViewActivity", "gl resolution: " + mMaxTexture);
        mTargetSize = new ImageSize(mMaxTexture, mMaxTexture);

        loadAdapter();

        // displaying selected image first
        mViewPager.setCurrentItem(position);
        if(utils.getFilePaths().size()>0){
            toolbar.setTitle((position+1)+"/"+utils.getFilePaths().size());
        }else{
            toolbar.setTitle("No Images");
        }

        mViewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

            }

            @Override
            public void onPageSelected(int position) {
                toolbar.setTitle((position+1)+"/"+utils.getFilePaths().size());
            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        });

        deleteDialog();

    }

    private void deleteDialog() {
        deleteConfirmBuilder = new AlertDialog.Builder(this);
        deleteConfirmBuilder.setTitle(getString(R.string.confirm_title));
        deleteConfirmBuilder.setMessage(getString(R.string.confirm_delete_text));
        deleteConfirmBuilder.setPositiveButton(getString(R.string.answer_yes), new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                deleteImage();
                dialog.dismiss();
            }

        });
        deleteConfirmBuilder.setNegativeButton(getString(R.string.answer_no), new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
    }

    private void setUpToolbar() {
        setSupportActionBar(toolbar);
        getSupportActionBar().setHomeButtonEnabled(true);
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    private void loadAdapter() {
        mViewPager.setAdapter(null);
        mAdapter = new FullScreenImageAdapter(FullScreenViewActivity.this, utils.getFilePaths());
        mAdapter.setImageLoader(mImageLoader);
        mAdapter.setMaxTexture(mMaxTexture, mTargetSize);
        mViewPager.setAdapter(mAdapter);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_imagepager, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch(id) {
            case R.id.action_delete:
                if(utils.getFilePaths().size()>0){
                    deleteConfirmBuilder.create().show();
                }else{
                    Intent intent = new Intent(FullScreenViewActivity.this,OpenNoteScannerActivity.class);
                    startActivity(intent);
                    finish();
                }
                return true;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void deleteImage() {
        int item = mViewPager.getCurrentItem();

        String filePath = mAdapter.getPath(item);
        final File photoFile = new File(filePath);

        photoFile.delete();
        Utils.removeImageFromGallery(filePath,this);

        loadAdapter();

        System.out.println("FullScreenViewActivity.deleteImage="+item);

        if( item >= (utils.getFilePaths().size())){
            mViewPager.setCurrentItem(item-1);
            toolbar.setTitle((item)+"/"+utils.getFilePaths().size());
        }else{
            mViewPager.setCurrentItem(item);
            toolbar.setTitle((item+1)+"/"+utils.getFilePaths().size());
        }
    }

}
